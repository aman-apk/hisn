/*
 *  Copyright (C) 2026 Hisn Project
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 or (at your option)
 *  version 3 of the License.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "SyncServer.h"

#include "core/Config.h"
#include "core/Database.h"
#include "core/Merger.h"
#include "core/Tools.h"
#include "format/KeePass2Reader.h"
#include "format/KeePass2Writer.h"
#include "sync/SyncCrypto.h"

#include <QBuffer>
#include <QDeadlineTimer>
#include <QFileInfo>
#include <QHostAddress>
#include <QHostInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutexLocker>
#include <QNetworkInterface>
#include <QSysInfo>
#include <QTcpSocket>
#include <QThread>

#include <algorithm>
#include <limits>
#include <utility>

namespace
{
    void appendU16LE(QByteArray& out, quint16 value)
    {
        out.append(static_cast<char>(value & 0xFF));
        out.append(static_cast<char>((value >> 8) & 0xFF));
    }

    void appendU32LE(QByteArray& out, quint32 value)
    {
        for (int i = 0; i < 4; ++i) {
            out.append(static_cast<char>((value >> (8 * i)) & 0xFF));
        }
    }

    void appendU64LE(QByteArray& out, quint64 value)
    {
        for (int i = 0; i < 8; ++i) {
            out.append(static_cast<char>((value >> (8 * i)) & 0xFF));
        }
    }

    quint16 readU16LE(const QByteArray& data, int offset)
    {
        return static_cast<quint16>(static_cast<quint8>(data.at(offset)))
               | static_cast<quint16>(static_cast<quint8>(data.at(offset + 1)) << 8);
    }

    quint32 readU32LE(const QByteArray& data, int offset)
    {
        quint32 value = 0;
        for (int i = 0; i < 4; ++i) {
            value |= static_cast<quint32>(static_cast<quint8>(data.at(offset + i))) << (8 * i);
        }
        return value;
    }

    quint64 readU64LE(const QByteArray& data, int offset)
    {
        quint64 value = 0;
        for (int i = 0; i < 8; ++i) {
            value |= static_cast<quint64>(static_cast<quint8>(data.at(offset + i))) << (8 * i);
        }
        return value;
    }

    /** A copy that does not share its buffer, so that wiping it actually erases the bytes. */
    QByteArray unsharedCopy(const QByteArray& data)
    {
        return {data.constData(), data.size()};
    }

    /** Cuts @p text down to @p maxBytes of UTF-8 without leaving half a character behind. */
    QByteArray toUtf8Limited(const QString& text, int maxBytes)
    {
        QByteArray utf8 = text.toUtf8();
        if (utf8.size() <= maxBytes) {
            return utf8;
        }
        utf8.truncate(maxBytes);
        while (!utf8.isEmpty() && (static_cast<quint8>(utf8.at(utf8.size() - 1)) & 0xC0) == 0x80) {
            utf8.chop(1);
        }
        if (!utf8.isEmpty() && (static_cast<quint8>(utf8.at(utf8.size() - 1)) & 0x80) != 0) {
            utf8.chop(1);
        }
        return utf8;
    }

    QByteArray encodeHello(const QString& deviceName)
    {
        const QByteArray name = toUtf8Limited(deviceName, SyncWire::MaxDeviceNameBytes);
        QByteArray body;
        body.reserve(3 + name.size());
        appendU16LE(body, SyncWire::Version);
        body.append(static_cast<char>(name.size()));
        body.append(name);
        return body;
    }

    bool decodeHello(const QByteArray& body, QString& deviceName)
    {
        if (body.size() < 3 || readU16LE(body, 0) != SyncWire::Version) {
            return false;
        }
        const int length = static_cast<quint8>(body.at(2));
        if (length > SyncWire::MaxDeviceNameBytes || body.size() < 3 + length) {
            return false;
        }
        deviceName = QString::fromUtf8(body.constData() + 3, length);
        return true;
    }

    QByteArray encodeOffer(qint64 size, const QByteArray& sha256)
    {
        QByteArray body;
        body.reserve(40);
        appendU64LE(body, static_cast<quint64>(size));
        body.append(sha256);
        return body;
    }

    QByteArray encodeError(const QString& message)
    {
        const QByteArray text = toUtf8Limited(message, SyncWire::MaxErrorBytes);
        QByteArray body;
        body.reserve(2 + text.size());
        appendU16LE(body, static_cast<quint16>(text.size()));
        body.append(text);
        return body;
    }

    QString decodeError(const QByteArray& body)
    {
        if (body.size() < 2) {
            return {};
        }
        const int length = readU16LE(body, 0);
        if (length > SyncWire::MaxErrorBytes || body.size() < 2 + length) {
            return {};
        }
        return QString::fromUtf8(body.constData() + 2, length);
    }

    bool isPrivateIPv4(const QHostAddress& address)
    {
        const quint32 raw = address.toIPv4Address();
        return (raw & 0xFF000000u) == 0x0A000000u // 10.0.0.0/8
               || (raw & 0xFFF00000u) == 0xAC100000u // 172.16.0.0/12
               || (raw & 0xFFFF0000u) == 0xC0A80000u; // 192.168.0.0/16
    }

    /**
     * Container bridges and VPN tunnels are up, carry private addresses and are useless for
     * reaching a phone, so a real wired or wireless interface always wins.
     */
    bool isVirtualInterface(const QString& name)
    {
        static const QStringList prefixes = {QStringLiteral("docker"),
                                             QStringLiteral("virbr"),
                                             QStringLiteral("br-"),
                                             QStringLiteral("veth"),
                                             QStringLiteral("vmnet"),
                                             QStringLiteral("tun"),
                                             QStringLiteral("tap"),
                                             QStringLiteral("utun"),
                                             QStringLiteral("wg"),
                                             QStringLiteral("zt"),
                                             QStringLiteral("lxc")};
        return std::any_of(prefixes.cbegin(), prefixes.cend(), [&name](const QString& prefix) {
            return name.startsWith(prefix, Qt::CaseInsensitive);
        });
    }
} // namespace

QByteArray SyncWire::magic()
{
    return {SyncWire::Magic, SyncWire::MagicBytes};
}

QString SyncWire::typeName(quint8 type)
{
    switch (type) {
    case MsgHello:
        return QStringLiteral("HELLO");
    case MsgDbOffer:
        return QStringLiteral("DB_OFFER");
    case MsgDbData:
        return QStringLiteral("DB_DATA");
    case MsgDbRequest:
        return QStringLiteral("DB_REQUEST");
    case MsgMergedDb:
        return QStringLiteral("MERGED_DB");
    case MsgError:
        return QStringLiteral("ERROR");
    default:
        return QStringLiteral("0x%1").arg(type, 2, 16, QLatin1Char('0'));
    }
}

/* -------------------------------------------------------------------------- SyncMergeChannel */

void SyncMergeChannel::abort()
{
    QMutexLocker locker(&m_mutex);
    m_aborted = true;
    SyncCrypto::wipe(m_input);
    SyncCrypto::wipe(m_merged);
    m_condition.wakeAll();
}

bool SyncMergeChannel::isAborted() const
{
    QMutexLocker locker(&m_mutex);
    return m_aborted;
}

void SyncMergeChannel::setInput(QByteArray&& incoming)
{
    QMutexLocker locker(&m_mutex);
    m_input = std::move(incoming);
    m_hasResult = false;
}

QByteArray SyncMergeChannel::takeInput()
{
    QMutexLocker locker(&m_mutex);
    return std::move(m_input);
}

void SyncMergeChannel::provideResult(QByteArray&& merged, int added, int updated, int deleted, const QString& error)
{
    QMutexLocker locker(&m_mutex);
    m_merged = std::move(merged);
    m_added = added;
    m_updated = updated;
    m_deleted = deleted;
    m_error = error;
    m_hasResult = true;
    m_condition.wakeAll();
}

bool SyncMergeChannel::waitForResult(QByteArray& merged, int& added, int& updated, int& deleted, QString& error)
{
    QMutexLocker locker(&m_mutex);
    QDeadlineTimer deadline(SyncWire::MergeTimeoutMs);
    while (!m_hasResult && !m_aborted && !deadline.hasExpired()) {
        m_condition.wait(&m_mutex, QDeadlineTimer(SyncWire::PollIntervalMs));
    }

    if (!m_hasResult) {
        error = m_aborted ? QObject::tr("تم إغلاق نافذة المزامنة قبل اكتمال الدمج")
                          : QObject::tr("استغرق دمج قاعدة البيانات وقتًا أطول من المتوقع");
        return false;
    }

    merged = std::move(m_merged);
    m_merged.clear();
    added = m_added;
    updated = m_updated;
    deleted = m_deleted;
    error = m_error;
    m_hasResult = false;
    return error.isEmpty();
}

/* ------------------------------------------------------------------------------- SyncSession */

SyncSession::SyncSession(qintptr socketDescriptor,
                         const QByteArray& staticPrivateKey,
                         const QByteArray& staticPublicKey,
                         const QByteArray& pairingSecret,
                         QString deviceName,
                         QSharedPointer<SyncMergeChannel> channel)
    : m_socketDescriptor(socketDescriptor)
    , m_staticPrivateKey(unsharedCopy(staticPrivateKey))
    , m_staticPublicKey(staticPublicKey)
    , m_pairingSecret(unsharedCopy(pairingSecret))
    , m_deviceName(std::move(deviceName))
    , m_channel(std::move(channel))
{
}

SyncSession::~SyncSession()
{
    SyncCrypto::wipe(m_staticPrivateKey);
    SyncCrypto::wipe(m_pairingSecret);
    SyncCrypto::wipe(m_sendKey);
    SyncCrypto::wipe(m_receiveKey);
    SyncCrypto::wipe(m_sendNoncePrefix);
    SyncCrypto::wipe(m_receiveNoncePrefix);
}

bool SyncSession::aborted() const
{
    return m_channel.isNull() || m_channel->isAborted();
}

bool SyncSession::fail(const QString& reason)
{
    if (m_error.isEmpty()) {
        m_error = reason;
    }
    return false;
}

QString SyncSession::sanitize(const QString& text, int maxLength)
{
    QString clean;
    clean.reserve(qMin(text.size(), static_cast<qsizetype>(maxLength)));
    for (const auto character : text) {
        if (clean.size() >= maxLength) {
            break;
        }
        if (!character.isPrint() && !character.isSpace()) {
            continue;
        }
        clean.append(character);
    }
    return clean.simplified();
}

void SyncSession::run()
{
    QTcpSocket socket;
    m_socket = &socket;

    if (!socket.setSocketDescriptor(m_socketDescriptor)) {
        fail(tr("تعذر قبول الاتصال الوارد"));
    } else {
        socket.setSocketOption(QAbstractSocket::LowDelayOption, 1);
        if (handshake()) {
            exchange();
        }
    }

    if (!m_error.isEmpty()) {
        if (m_transportUsable) {
            sendErrorFrame(m_error);
        }
        if (!m_reported && !aborted()) {
            m_reported = true;
            emit syncFailed(m_error);
        }
    }

    socket.disconnectFromHost();
    if (socket.state() != QAbstractSocket::UnconnectedState) {
        socket.waitForDisconnected(SyncWire::PollIntervalMs);
    }
    socket.close();
    m_socket = nullptr;

    SyncCrypto::wipe(m_sendKey);
    SyncCrypto::wipe(m_receiveKey);
    SyncCrypto::wipe(m_sendNoncePrefix);
    SyncCrypto::wipe(m_receiveNoncePrefix);
    SyncCrypto::wipe(m_pairingSecret);
    SyncCrypto::wipe(m_staticPrivateKey);

    emit finished();
}

SyncSession::ReadResult SyncSession::readBytes(QByteArray& out, int size, int timeoutMs, bool allowCleanClose)
{
    out.clear();
    if (size <= 0) {
        return ReadResult::Ok;
    }
    // Every caller validates the length before it gets here, so this allocation is bounded.
    out.reserve(size);

    QDeadlineTimer deadline(qMax(timeoutMs, 1));
    while (out.size() < size) {
        if (aborted()) {
            fail(tr("تم إغلاق نافذة المزامنة"));
            return ReadResult::Failed;
        }

        const qint64 available = m_socket->bytesAvailable();
        if (available > 0) {
            out.append(m_socket->read(qMin<qint64>(size - out.size(), available)));
            continue;
        }
        if (m_socket->state() != QAbstractSocket::ConnectedState) {
            if (allowCleanClose && out.isEmpty()) {
                return ReadResult::Closed;
            }
            fail(tr("أغلق الجهاز الآخر الاتصال"));
            return ReadResult::Failed;
        }
        if (deadline.hasExpired()) {
            fail(tr("توقف الجهاز الآخر عن الاستجابة"));
            return ReadResult::Failed;
        }

        // Poll in slices so that closing the sync window is noticed quickly.
        const qint64 remaining = deadline.remainingTime();
        const int slice = static_cast<int>(qBound<qint64>(1, remaining, qint64(SyncWire::PollIntervalMs)));
        m_socket->waitForReadyRead(slice);
    }
    return ReadResult::Ok;
}

bool SyncSession::writeBytes(const QByteArray& data)
{
    qint64 written = 0;
    while (written < data.size()) {
        if (aborted()) {
            return fail(tr("تم إغلاق نافذة المزامنة"));
        }
        if (m_socket->state() != QAbstractSocket::ConnectedState) {
            return fail(tr("أغلق الجهاز الآخر الاتصال"));
        }

        const qint64 chunk = m_socket->write(data.constData() + written, data.size() - written);
        if (chunk < 0) {
            return fail(tr("تعذر إرسال البيانات إلى الجهاز الآخر"));
        }
        written += chunk;

        QDeadlineTimer deadline(SyncWire::FrameTimeoutMs);
        while (m_socket->bytesToWrite() > 0) {
            if (aborted()) {
                return fail(tr("تم إغلاق نافذة المزامنة"));
            }
            if (!m_socket->waitForBytesWritten(SyncWire::PollIntervalMs)) {
                if (m_socket->state() != QAbstractSocket::ConnectedState) {
                    return fail(tr("أغلق الجهاز الآخر الاتصال"));
                }
                if (deadline.hasExpired()) {
                    return fail(tr("توقف الجهاز الآخر عن الاستجابة"));
                }
            }
        }
    }
    return true;
}

bool SyncSession::handshake()
{
    QDeadlineTimer deadline(SyncWire::HandshakeTimeoutMs);
    const QByteArray magic = SyncWire::magic();

    QByteArray clientHello;
    if (readBytes(
            clientHello, SyncWire::ClientHelloBytes, static_cast<int>(qMax<qint64>(1, deadline.remainingTime())), false)
        != ReadResult::Ok) {
        return false;
    }
    if (!clientHello.startsWith(magic)) {
        return fail(tr("الجهاز المتصل لا يتحدث بروتوكول مزامنة حصن"));
    }
    const quint16 peerVersion = readU16LE(clientHello, SyncWire::MagicBytes);
    if (peerVersion != SyncWire::Version) {
        return fail(tr("الجهاز الآخر يستخدم إصدار مزامنة مختلفًا (%1)").arg(peerVersion));
    }
    const QByteArray clientEphemeral = clientHello.mid(10, SyncCrypto::KeyBytes);

    QByteArray ephemeralPrivate;
    QByteArray ephemeralPublic;
    if (!SyncCrypto::generateKeyPair(ephemeralPrivate, ephemeralPublic)) {
        return fail(tr("تعذر إنشاء مفاتيح الجلسة"));
    }

    QByteArray ee;
    QByteArray es;
    const bool agreed = SyncCrypto::agree(ephemeralPrivate, clientEphemeral, ee)
                        && SyncCrypto::agree(m_staticPrivateKey, clientEphemeral, es);
    SyncCrypto::wipe(ephemeralPrivate);
    if (!agreed) {
        SyncCrypto::wipe(ee);
        SyncCrypto::wipe(es);
        return fail(tr("أرسل الجهاز الآخر مفتاحًا عامًا غير صالح"));
    }

    QByteArray transcript;
    transcript.reserve(SyncWire::TranscriptBytes);
    transcript.append(magic);
    appendU16LE(transcript, SyncWire::Version);
    transcript.append(clientEphemeral);
    transcript.append(ephemeralPublic);
    transcript.append(m_staticPublicKey);

    const QByteArray salt = SyncCrypto::sha256(transcript);
    QByteArray ikm;
    ikm.reserve(3 * SyncCrypto::KeyBytes);
    ikm.append(ee);
    ikm.append(es);
    ikm.append(m_pairingSecret);
    SyncCrypto::wipe(ee);
    SyncCrypto::wipe(es);

    m_receiveKey = SyncCrypto::hkdf(salt, ikm, SyncWire::InfoKeyC2S, SyncCrypto::KeyBytes);
    m_sendKey = SyncCrypto::hkdf(salt, ikm, SyncWire::InfoKeyS2C, SyncCrypto::KeyBytes);
    m_receiveNoncePrefix = SyncCrypto::hkdf(salt, ikm, SyncWire::InfoNonceC2S, SyncCrypto::NoncePrefixBytes);
    m_sendNoncePrefix = SyncCrypto::hkdf(salt, ikm, SyncWire::InfoNonceS2C, SyncCrypto::NoncePrefixBytes);
    QByteArray confirmServer = SyncCrypto::hkdf(salt, ikm, SyncWire::InfoConfirmServer, SyncWire::ConfirmBytes);
    QByteArray confirmClient = SyncCrypto::hkdf(salt, ikm, SyncWire::InfoConfirmClient, SyncWire::ConfirmBytes);
    SyncCrypto::wipe(ikm);

    if (m_sendKey.size() != SyncCrypto::KeyBytes || m_receiveKey.size() != SyncCrypto::KeyBytes
        || m_sendNoncePrefix.size() != SyncCrypto::NoncePrefixBytes
        || m_receiveNoncePrefix.size() != SyncCrypto::NoncePrefixBytes || confirmServer.size() != SyncWire::ConfirmBytes
        || confirmClient.size() != SyncWire::ConfirmBytes) {
        SyncCrypto::wipe(confirmServer);
        SyncCrypto::wipe(confirmClient);
        return fail(tr("تعذر اشتقاق مفاتيح الجلسة"));
    }

    QByteArray serverHello;
    serverHello.reserve(SyncWire::ServerHelloBytes);
    serverHello.append(magic);
    appendU16LE(serverHello, SyncWire::Version);
    serverHello.append(ephemeralPublic);
    serverHello.append(m_staticPublicKey);
    serverHello.append(confirmServer);
    SyncCrypto::wipe(confirmServer);

    if (serverHello.size() != SyncWire::ServerHelloBytes) {
        SyncCrypto::wipe(confirmClient);
        return fail(tr("تعذر اشتقاق مفاتيح الجلسة"));
    }
    if (!writeBytes(serverHello)) {
        SyncCrypto::wipe(confirmClient);
        return false;
    }

    QByteArray clientConfirm;
    const auto result = readBytes(clientConfirm,
                                  SyncWire::ClientConfirmBytes,
                                  static_cast<int>(qMax<qint64>(1, deadline.remainingTime())),
                                  false);
    const bool confirmed = result == ReadResult::Ok && SyncCrypto::constantTimeEquals(clientConfirm, confirmClient);
    SyncCrypto::wipe(confirmClient);
    if (!confirmed) {
        // Nothing further is sent: the peer either does not know the pairing secret or gave up.
        return fail(m_error.isEmpty() ? tr("رمز الاقتران لا يطابق هذا الجهاز") : m_error);
    }

    m_transportUsable = true;
    return true;
}

bool SyncSession::sendFrame(quint8 type, const QByteArray& body)
{
    if (body.size() + 1 > SyncWire::MaxFramePlaintext) {
        return fail(tr("رسالة أكبر من الحد المسموح به"));
    }
    if (m_sendCounter == std::numeric_limits<quint64>::max()) {
        return fail(tr("انتهى عدّاد الرسائل"));
    }

    QByteArray plaintext;
    plaintext.reserve(body.size() + 1);
    plaintext.append(static_cast<char>(type));
    plaintext.append(body);

    QByteArray header;
    appendU32LE(header, static_cast<quint32>(plaintext.size() + SyncCrypto::TagBytes));

    QByteArray sealed;
    const bool ok = SyncCrypto::seal(
        m_sendKey, SyncCrypto::frameNonce(m_sendNoncePrefix, m_sendCounter), header, plaintext, sealed);
    SyncCrypto::wipe(plaintext);
    if (!ok) {
        return fail(tr("تعذر تشفير الرسالة"));
    }
    ++m_sendCounter;

    return writeBytes(header + sealed);
}

SyncSession::ReadResult SyncSession::readFrame(quint8& type, QByteArray& body, bool allowCleanClose)
{
    QByteArray header;
    const auto headerResult = readBytes(header, 4, SyncWire::FrameTimeoutMs, allowCleanClose);
    if (headerResult != ReadResult::Ok) {
        return headerResult;
    }

    // Validate before allocating: a hostile peer must not be able to ask for a huge buffer.
    const quint32 length = readU32LE(header, 0);
    if (length < static_cast<quint32>(SyncCrypto::TagBytes + 1)
        || length > static_cast<quint32>(SyncWire::MaxFrameCiphertext)) {
        fail(tr("أرسل الجهاز الآخر رسالة بطول غير صالح"));
        return ReadResult::Failed;
    }
    if (m_receiveCounter == std::numeric_limits<quint64>::max()) {
        fail(tr("انتهى عدّاد الرسائل"));
        return ReadResult::Failed;
    }

    QByteArray sealed;
    if (readBytes(sealed, static_cast<int>(length), SyncWire::FrameTimeoutMs, false) != ReadResult::Ok) {
        return ReadResult::Failed;
    }

    QByteArray plaintext;
    if (!SyncCrypto::open(
            m_receiveKey, SyncCrypto::frameNonce(m_receiveNoncePrefix, m_receiveCounter), header, sealed, plaintext)) {
        fail(tr("فشل التحقق من رسالة واردة"));
        return ReadResult::Failed;
    }
    ++m_receiveCounter;

    type = static_cast<quint8>(plaintext.at(0));
    body = plaintext.mid(1);
    SyncCrypto::wipe(plaintext);

    if (type == SyncWire::MsgError) {
        const QString reported = sanitize(decodeError(body), 256);
        // The peer has already given up, so do not answer with an ERROR of our own.
        m_transportUsable = false;
        fail(reported.isEmpty() ? tr("أبلغ الجهاز الآخر عن خطأ") : reported);
        return ReadResult::Failed;
    }
    return ReadResult::Ok;
}

void SyncSession::sendErrorFrame(const QString& reason)
{
    m_transportUsable = false;
    sendFrame(SyncWire::MsgError, encodeError(reason));
}

bool SyncSession::exchange()
{
    quint8 type = 0;
    QByteArray body;

    if (readFrame(type, body, false) != ReadResult::Ok) {
        return false;
    }
    if (type != SyncWire::MsgHello) {
        return fail(tr("توقعنا HELLO ووصل %1").arg(SyncWire::typeName(type)));
    }
    QString peerName;
    if (!decodeHello(body, peerName)) {
        return fail(tr("رسالة تعريف غير صالحة من الجهاز الآخر"));
    }
    m_peerName = sanitize(peerName, SyncWire::MaxDeviceNameBytes);
    if (m_peerName.isEmpty()) {
        m_peerName = tr("جهاز غير معروف");
    }
    emit clientConnected(m_peerName);

    if (!sendFrame(SyncWire::MsgHello, encodeHello(m_deviceName))) {
        return false;
    }

    // A client that is only verifying a pairing hangs up here. That is a success, not an error.
    const auto next = readFrame(type, body, true);
    if (next == ReadResult::Closed) {
        m_reported = true;
        emit pairingSucceeded(m_peerName);
        return true;
    }
    if (next != ReadResult::Ok) {
        return false;
    }
    if (type != SyncWire::MsgDbOffer) {
        return fail(tr("توقعنا DB_OFFER ووصل %1").arg(SyncWire::typeName(type)));
    }

    QByteArray received;
    if (!receiveDatabase(body, received)) {
        SyncCrypto::wipe(received);
        return false;
    }

    if (readFrame(type, body, false) != ReadResult::Ok) {
        SyncCrypto::wipe(received);
        return false;
    }
    if (type != SyncWire::MsgDbRequest) {
        SyncCrypto::wipe(received);
        return fail(tr("توقعنا DB_REQUEST ووصل %1").arg(SyncWire::typeName(type)));
    }

    // Hand the bytes to the GUI thread, which owns the open database, and wait for the merge.
    if (aborted()) {
        SyncCrypto::wipe(received);
        return fail(tr("تم إغلاق نافذة المزامنة"));
    }
    m_channel->setInput(std::move(received));
    emit mergeRequested();

    QByteArray merged;
    int added = 0;
    int updated = 0;
    int deleted = 0;
    QString mergeError;
    if (!m_channel->waitForResult(merged, added, updated, deleted, mergeError)) {
        SyncCrypto::wipe(merged);
        return fail(mergeError.isEmpty() ? tr("فشل دمج قاعدة البيانات") : mergeError);
    }

    const bool sent = sendDatabase(merged);
    SyncCrypto::wipe(merged);
    if (!sent) {
        return false;
    }

    m_reported = true;
    emit syncFinished(added, updated, deleted);
    return true;
}

bool SyncSession::receiveDatabase(const QByteArray& offerBody, QByteArray& received)
{
    if (offerBody.size() < 40) {
        return fail(tr("عرض قاعدة بيانات غير صالح"));
    }
    const quint64 size = readU64LE(offerBody, 0);
    if (size == 0 || size > static_cast<quint64>(SyncWire::MaxDatabaseBytes)) {
        return fail(tr("حجم قاعدة البيانات المعروضة غير معقول"));
    }
    const QByteArray expectedHash = offerBody.mid(8, SyncCrypto::HashBytes);
    const qint64 total = static_cast<qint64>(size);

    received.clear();
    received.reserve(static_cast<qsizetype>(size));
    emit syncProgress(0, total);

    quint32 expectedIndex = 0;
    while (static_cast<quint64>(received.size()) < size) {
        quint8 type = 0;
        QByteArray body;
        if (readFrame(type, body, false) != ReadResult::Ok) {
            return false;
        }
        if (type != SyncWire::MsgDbData) {
            return fail(tr("توقعنا DB_DATA ووصل %1").arg(SyncWire::typeName(type)));
        }
        if (body.size() < 8) {
            return fail(tr("جزء بيانات غير صالح"));
        }
        const quint32 index = readU32LE(body, 0);
        const quint32 length = readU32LE(body, 4);
        if (index != expectedIndex) {
            return fail(tr("وصلت أجزاء البيانات بترتيب غير صحيح"));
        }
        if (length < 1 || length > static_cast<quint32>(SyncWire::ChunkBytes)
            || body.size() < static_cast<qsizetype>(8 + length)) {
            return fail(tr("جزء بيانات غير صالح"));
        }
        if (static_cast<quint64>(received.size()) + length > size) {
            return fail(tr("أرسل الجهاز الآخر بيانات أكثر مما أعلن عنه"));
        }

        received.append(body.constData() + 8, static_cast<qsizetype>(length));
        ++expectedIndex;
        emit syncProgress(received.size(), total);
    }

    if (!SyncCrypto::constantTimeEquals(SyncCrypto::sha256(received), expectedHash)) {
        return fail(tr("قاعدة البيانات المستلمة تالفة (بصمة التحقق غير مطابقة)"));
    }
    return true;
}

bool SyncSession::sendDatabase(const QByteArray& database)
{
    const qint64 total = database.size();
    if (total <= 0 || total > SyncWire::MaxDatabaseBytes) {
        return fail(tr("قاعدة البيانات المدموجة أكبر من الحد المسموح به"));
    }

    if (!sendFrame(SyncWire::MsgMergedDb, encodeOffer(total, SyncCrypto::sha256(database)))) {
        return false;
    }
    emit syncProgress(0, total);

    qint64 sent = 0;
    quint32 index = 0;
    while (sent < total) {
        const int length = static_cast<int>(qMin<qint64>(SyncWire::ChunkBytes, total - sent));
        QByteArray chunk;
        chunk.reserve(8 + length);
        appendU32LE(chunk, index);
        appendU32LE(chunk, static_cast<quint32>(length));
        chunk.append(database.constData() + sent, length);

        const bool ok = sendFrame(SyncWire::MsgDbData, chunk);
        SyncCrypto::wipe(chunk);
        if (!ok) {
            return false;
        }

        sent += length;
        ++index;
        emit syncProgress(sent, total);
    }
    return true;
}

/* -------------------------------------------------------------------------------- SyncServer */

SyncServer::SyncServer(QObject* parent)
    : QTcpServer(parent)
{
}

SyncServer::~SyncServer()
{
    stop();
}

bool SyncServer::start(const QSharedPointer<Database>& database, QString* errorMessage)
{
    stop();

    const auto setError = [errorMessage](const QString& message) {
        if (errorMessage) {
            *errorMessage = message;
        }
        return false;
    };

    if (!database || !database->isInitialized()) {
        return setError(tr("لا توجد قاعدة بيانات مفتوحة"));
    }
    if (database->filePath().isEmpty()) {
        return setError(tr("احفظ قاعدة البيانات في ملف قبل بدء المزامنة"));
    }
    if (!loadStaticKey(errorMessage)) {
        return false;
    }
    loadPairingSecret();

    m_address = bestLanAddress();
    if (m_address.isEmpty()) {
        return setError(tr("تعذر العثور على عنوان شبكة محلية على هذا الجهاز"));
    }
    m_deviceName = localDeviceName();

    if (!listen(QHostAddress::Any, 0)) {
        return setError(tr("تعذر فتح منفذ للاستماع: %1").arg(errorString()));
    }

    m_database = database;
    return true;
}

void SyncServer::stop()
{
    if (isListening()) {
        close();
    }
    if (m_channel) {
        m_channel->abort();
    }
    if (m_thread) {
        // The session polls the abort flag, so this returns promptly. If it somehow does not, the
        // thread deletes itself once it finishes; nothing here keeps a reference to it.
        m_thread->quit();
        m_thread->wait(2000);
        m_thread.clear();
    }
    m_channel.clear();
    m_database.clear();

    SyncCrypto::wipe(m_pairingSecret);
    SyncCrypto::wipe(m_staticPrivateKey);
    m_staticPublicKey.clear();
    m_address.clear();
}

QString SyncServer::address() const
{
    return m_address;
}

QString SyncServer::deviceName() const
{
    return m_deviceName;
}

QByteArray SyncServer::pairingPayload() const
{
    if (!isListening() || m_pairingSecret.size() != SyncWire::PairingSecretBytes
        || m_staticPublicKey.size() != SyncCrypto::KeyBytes) {
        return {};
    }

    QJsonObject payload;
    payload[QStringLiteral("v")] = static_cast<int>(SyncWire::Version);
    payload[QStringLiteral("n")] = m_deviceName;
    payload[QStringLiteral("h")] = m_address;
    payload[QStringLiteral("p")] = static_cast<int>(serverPort());
    payload[QStringLiteral("k")] = QString::fromLatin1(m_pairingSecret.toBase64());
    payload[QStringLiteral("f")] = QString::fromLatin1(SyncCrypto::sha256(m_staticPublicKey).toBase64());
    return QJsonDocument(payload).toJson(QJsonDocument::Compact);
}

void SyncServer::incomingConnection(qintptr socketDescriptor)
{
    if (m_thread) {
        // One connection at a time; a second caller is dropped without ceremony.
        QTcpSocket rejected;
        rejected.setSocketDescriptor(socketDescriptor);
        rejected.close();
        return;
    }

    pauseAccepting();
    m_channel = QSharedPointer<SyncMergeChannel>::create();

    auto* session = new SyncSession(
        socketDescriptor, m_staticPrivateKey, m_staticPublicKey, m_pairingSecret, m_deviceName, m_channel);
    auto* thread = new QThread();
    thread->setObjectName(QStringLiteral("HisnSyncSession"));
    session->moveToThread(thread);

    connect(thread, &QThread::started, session, &SyncSession::run);
    // Direct so that the session thread stops even if the GUI event loop is already gone.
    connect(session, &SyncSession::finished, thread, &QThread::quit, Qt::DirectConnection);
    connect(thread, &QThread::finished, session, &QObject::deleteLater);

    connect(session, &SyncSession::clientConnected, this, &SyncServer::handleClientConnected);
    connect(session, &SyncSession::pairingSucceeded, this, &SyncServer::pairingSucceeded);
    connect(session, &SyncSession::syncProgress, this, &SyncServer::syncProgress);
    connect(session, &SyncSession::syncFinished, this, &SyncServer::syncFinished);
    connect(session, &SyncSession::syncFailed, this, &SyncServer::syncFailed);
    connect(session, &SyncSession::mergeRequested, this, &SyncServer::handleMergeRequest);

    // Connected before deleteLater so that the bookkeeping runs while the thread object is alive.
    connect(thread, &QThread::finished, this, [this, thread] {
        if (m_thread.data() == thread) {
            handleSessionEnded();
        }
    });
    connect(thread, &QThread::finished, thread, &QObject::deleteLater);

    m_thread = thread;
    thread->start();
}

void SyncServer::handleClientConnected(QString peerName)
{
    // The phone keeps the secret it paired with and reuses it for every later sync, so the desktop
    // has to keep issuing the same one once a pairing has actually worked.
    if (!m_pairingSecretStored && m_pairingSecret.size() == SyncWire::PairingSecretBytes) {
        config()->set(Config::Sync_PairingSecret, QString::fromLatin1(m_pairingSecret.toBase64()));
        m_pairingSecretStored = true;
    }
    emit clientConnected(peerName);
}

void SyncServer::handleSessionEnded()
{
    m_thread.clear();
    m_channel.clear();
    if (isListening()) {
        resumeAccepting();
    }
}

void SyncServer::handleMergeRequest()
{
    auto channel = m_channel;
    if (!channel) {
        return;
    }

    QByteArray incoming = channel->takeInput();
    QByteArray merged;
    int added = 0;
    int updated = 0;
    int deleted = 0;
    QString error;
    const bool ok = mergeDatabase(incoming, merged, added, updated, deleted, error);
    SyncCrypto::wipe(incoming);

    if (!ok) {
        SyncCrypto::wipe(merged);
        channel->provideResult({}, 0, 0, 0, error.isEmpty() ? tr("فشل دمج قاعدة البيانات") : error);
        return;
    }
    channel->provideResult(std::move(merged), added, updated, deleted, QString());
}

bool SyncServer::mergeDatabase(const QByteArray& incoming,
                               QByteArray& merged,
                               int& added,
                               int& updated,
                               int& deleted,
                               QString& error)
{
    if (!m_database || !m_database->isInitialized()) {
        error = tr("لا توجد قاعدة بيانات مفتوحة");
        return false;
    }
    const auto key = m_database->key();
    if (key.isNull()) {
        error = tr("لا توجد قاعدة بيانات مفتوحة");
        return false;
    }
    if (m_database->filePath().isEmpty()) {
        error = tr("احفظ قاعدة البيانات في ملف قبل بدء المزامنة");
        return false;
    }

    // The phone's database has to open with this database's own key; otherwise the two vaults are
    // unrelated and merging them would be meaningless.
    QByteArray buffer = incoming;
    QBuffer device(&buffer);
    if (!device.open(QIODevice::ReadOnly)) {
        error = tr("تعذر قراءة قاعدة البيانات المستلمة");
        return false;
    }

    auto remoteDb = QSharedPointer<Database>::create();
    KeePass2Reader reader;
    const bool read = reader.readDatabase(&device, key, remoteDb.data());
    device.close();
    buffer.clear();
    if (!read) {
        error = tr("تعذر فتح قاعدة البيانات المستلمة بمفتاح هذه القاعدة: %1").arg(reader.errorString());
        return false;
    }
    remoteDb->markAsTemporaryDatabase();

    Merger merger(remoteDb.data(), m_database.data());
    const auto changes = merger.merge();
    for (const auto& change : changes) {
        switch (change.type()) {
        case Merger::Change::Type::Added:
            ++added;
            break;
        case Merger::Change::Type::Deleted:
            ++deleted;
            break;
        default:
            ++updated;
            break;
        }
    }

    if (m_database->isModified()) {
        Database::SaveAction saveAction = Database::Atomic;
        if (!config()->get(Config::UseAtomicSaves).toBool()) {
            saveAction =
                config()->get(Config::UseDirectWriteSaves).toBool() ? Database::DirectWrite : Database::TempFile;
        }

        QString backupFilePath;
        if (config()->get(Config::BackupBeforeSave).toBool()) {
            backupFilePath = config()->get(Config::BackupFilePathPattern).toString();
            if (backupFilePath.isEmpty()) {
                backupFilePath = config()->getDefault(Config::BackupFilePathPattern).toString();
            }
            const QFileInfo dbFileInfo(m_database->filePath());
            backupFilePath = Tools::substituteBackupFilePath(backupFilePath, dbFileInfo.canonicalFilePath());
        }

        QString saveError;
        if (!m_database->save(saveAction, backupFilePath, &saveError)) {
            error = tr("تعذر حفظ قاعدة البيانات بعد الدمج: %1").arg(saveError);
            return false;
        }
    }

    QBuffer output;
    if (!output.open(QIODevice::WriteOnly)) {
        error = tr("تعذر تجهيز قاعدة البيانات المدموجة للإرسال");
        return false;
    }
    KeePass2Writer writer;
    const bool written = writer.writeDatabase(&output, m_database.data());
    output.close();
    if (!written) {
        error = tr("تعذر تجهيز قاعدة البيانات المدموجة للإرسال: %1").arg(writer.errorString());
        return false;
    }
    merged = output.data();
    return true;
}

bool SyncServer::loadStaticKey(QString* errorMessage)
{
    QByteArray privateKey = QByteArray::fromBase64(config()->get(Config::Sync_StaticPrivateKey).toByteArray());
    const QByteArray storedPublicKey =
        QByteArray::fromBase64(config()->get(Config::Sync_StaticPublicKey).toByteArray());

    if (privateKey.size() == SyncCrypto::KeyBytes) {
        QByteArray derived;
        if (SyncCrypto::derivePublicKey(privateKey, derived)) {
            m_staticPrivateKey = privateKey;
            m_staticPublicKey = derived;
            if (storedPublicKey != derived) {
                config()->set(Config::Sync_StaticPublicKey, QString::fromLatin1(derived.toBase64()));
            }
            return true;
        }
    }
    SyncCrypto::wipe(privateKey);

    if (!SyncCrypto::generateKeyPair(m_staticPrivateKey, m_staticPublicKey)) {
        if (errorMessage) {
            *errorMessage = tr("تعذر إنشاء هوية المزامنة لهذا الجهاز");
        }
        return false;
    }
    config()->set(Config::Sync_StaticPrivateKey, QString::fromLatin1(m_staticPrivateKey.toBase64()));
    config()->set(Config::Sync_StaticPublicKey, QString::fromLatin1(m_staticPublicKey.toBase64()));
    return true;
}

void SyncServer::loadPairingSecret()
{
    // A device that has paired stores this secret and presents it again on every later sync, and
    // the handshake has no room for more than one secret at a time. A new one is therefore only
    // minted while no pairing has succeeded yet; after that the same secret is reissued.
    const QByteArray stored = QByteArray::fromBase64(config()->get(Config::Sync_PairingSecret).toByteArray());
    if (stored.size() == SyncWire::PairingSecretBytes) {
        m_pairingSecret = stored;
        m_pairingSecretStored = true;
        return;
    }

    m_pairingSecret = SyncCrypto::randomBytes(SyncWire::PairingSecretBytes);
    m_pairingSecretStored = false;
}

QString SyncServer::bestLanAddress()
{
    // Best first: a private address on a physical interface, then any private address, then any
    // routable IPv4 address at all.
    QString best;
    int bestRank = -1;

    const auto interfaces = QNetworkInterface::allInterfaces();
    for (const auto& iface : interfaces) {
        const auto flags = iface.flags();
        if (!flags.testFlag(QNetworkInterface::IsUp) || !flags.testFlag(QNetworkInterface::IsRunning)
            || flags.testFlag(QNetworkInterface::IsLoopBack)) {
            continue;
        }
        const bool physical = !isVirtualInterface(iface.name());

        const auto entries = iface.addressEntries();
        for (const auto& entry : entries) {
            const auto address = entry.ip();
            if (address.protocol() != QAbstractSocket::IPv4Protocol || address.isLoopback() || address.isLinkLocal()) {
                continue;
            }

            int rank = 0;
            if (isPrivateIPv4(address)) {
                rank = physical ? 2 : 1;
            }
            if (rank > bestRank) {
                bestRank = rank;
                best = address.toString();
            }
        }
    }
    return best;
}

QString SyncServer::localDeviceName()
{
    QString name = QSysInfo::machineHostName().trimmed();
    if (name.isEmpty()) {
        name = QHostInfo::localHostName().trimmed();
    }
    if (name.isEmpty()) {
        name = QStringLiteral("Hisn");
    }
    return QString::fromUtf8(toUtf8Limited(name, SyncWire::MaxDeviceNameBytes));
}
