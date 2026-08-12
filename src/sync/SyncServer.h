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

#ifndef HISN_SYNCSERVER_H
#define HISN_SYNCSERVER_H

#include <QMutex>
#include <QPointer>
#include <QSharedPointer>
#include <QTcpServer>
#include <QWaitCondition>

class Database;
class QTcpSocket;
class QThread;

/**
 * Wire constants of the Hisn LAN sync protocol, version 1. Changing any of these breaks
 * compatibility with the phone. See android/SYNC-PROTOCOL.md.
 */
namespace SyncWire
{
    constexpr char Magic[] = {'H', 'I', 'S', 'N', 'S', 'Y', 'N', '1'};
    constexpr int MagicBytes = 8;
    constexpr quint16 Version = 1;

    constexpr int PairingSecretBytes = 32;
    constexpr int ConfirmBytes = 32;
    constexpr int ClientHelloBytes = 42;
    constexpr int ServerHelloBytes = 106;
    constexpr int TranscriptBytes = 106;
    constexpr int ClientConfirmBytes = 32;

    constexpr int MaxFramePlaintext = 1 << 20;
    constexpr int MaxFrameCiphertext = MaxFramePlaintext + 16;
    constexpr int ChunkBytes = 64 * 1024;
    constexpr qint64 MaxDatabaseBytes = 64LL * 1024 * 1024;
    constexpr int MaxDeviceNameBytes = 64;
    constexpr int MaxErrorBytes = 1024;

    constexpr int HandshakeTimeoutMs = 10000;
    constexpr int FrameTimeoutMs = 30000;
    /** How long a blocked read waits before it looks at the abort flag again. */
    constexpr int PollIntervalMs = 200;
    /** Upper bound on how long the session thread waits for the GUI thread to finish a merge. */
    constexpr int MergeTimeoutMs = 300000;

    enum MessageType : quint8
    {
        MsgHello = 0x01,
        MsgDbOffer = 0x02,
        MsgDbData = 0x03,
        MsgDbRequest = 0x04,
        MsgMergedDb = 0x05,
        MsgError = 0x06,
    };

    // HKDF labels: ASCII, no terminating NUL.
    constexpr char InfoKeyC2S[] = "hisn-sync/1 key client->server";
    constexpr char InfoKeyS2C[] = "hisn-sync/1 key server->client";
    constexpr char InfoNonceC2S[] = "hisn-sync/1 nonce client->server";
    constexpr char InfoNonceS2C[] = "hisn-sync/1 nonce server->client";
    constexpr char InfoConfirmServer[] = "hisn-sync/1 confirm server";
    constexpr char InfoConfirmClient[] = "hisn-sync/1 confirm client";

    QByteArray magic();
    QString typeName(quint8 type);
} // namespace SyncWire

/**
 * Rendezvous point between the session thread and the GUI thread.
 *
 * The session thread never touches a Database. When it has a complete database from the phone it
 * parks the bytes here, asks the GUI thread to merge them and waits; the GUI thread does the merge
 * on the live Database and parks the answer here in turn. Both sides hold the channel through a
 * QSharedPointer, so it outlives whichever of them goes away first, and every wait is bounded and
 * interruptible so that closing the sync window can never deadlock the GUI.
 */
class SyncMergeChannel
{
public:
    /** Unblocks any waiter and makes every later wait fail immediately. */
    void abort();
    bool isAborted() const;

    /** Session thread: hands over the received database, giving up its own reference to it. */
    void setInput(QByteArray&& incoming);

    /** Session thread: blocks until the GUI thread answers, the wait times out, or abort(). */
    bool waitForResult(QByteArray& merged, int& added, int& updated, int& deleted, QString& error);

    /** GUI thread: takes the received database out of the channel. */
    QByteArray takeInput();

    /** GUI thread: publishes the merge result and wakes the session thread. */
    void provideResult(QByteArray&& merged, int added, int updated, int deleted, const QString& error);

private:
    mutable QMutex m_mutex;
    QWaitCondition m_condition;
    bool m_aborted = false;
    bool m_hasResult = false;
    QByteArray m_input;
    QByteArray m_merged;
    int m_added = 0;
    int m_updated = 0;
    int m_deleted = 0;
    QString m_error;
};

/**
 * One sync session, start to finish, on a worker thread.
 *
 * Everything here runs off the GUI thread: the socket is created from the accepted descriptor in
 * run() and never leaves this thread, and all blocking reads poll in short slices so that an abort
 * is noticed within SyncWire::PollIntervalMs. The only interaction with the rest of the
 * application is through queued signals and the SyncMergeChannel.
 */
class SyncSession : public QObject
{
    Q_OBJECT

public:
    SyncSession(qintptr socketDescriptor,
                const QByteArray& staticPrivateKey,
                const QByteArray& staticPublicKey,
                const QByteArray& pairingSecret,
                QString deviceName,
                QSharedPointer<SyncMergeChannel> channel);
    ~SyncSession() override;

signals:
    void clientConnected(QString deviceName);
    void pairingSucceeded(QString deviceName);
    void syncProgress(qint64 done, qint64 total);
    void syncFinished(int added, int updated, int deleted);
    void syncFailed(QString reason);
    void mergeRequested();
    void finished();

public slots:
    void run();

private:
    enum class ReadResult
    {
        Ok,
        Closed,
        Failed
    };

    bool aborted() const;
    bool fail(const QString& reason);

    ReadResult readBytes(QByteArray& out, int size, int timeoutMs, bool allowCleanClose);
    bool writeBytes(const QByteArray& data);

    bool handshake();
    bool exchange();
    bool receiveDatabase(const QByteArray& offerBody, QByteArray& received);
    bool sendDatabase(const QByteArray& database);

    bool sendFrame(quint8 type, const QByteArray& body = {});
    ReadResult readFrame(quint8& type, QByteArray& body, bool allowCleanClose);
    void sendErrorFrame(const QString& reason);

    static QString sanitize(const QString& text, int maxLength);

    qintptr m_socketDescriptor;
    QByteArray m_staticPrivateKey;
    QByteArray m_staticPublicKey;
    QByteArray m_pairingSecret;
    QString m_deviceName;
    QSharedPointer<SyncMergeChannel> m_channel;

    QTcpSocket* m_socket = nullptr;
    QString m_peerName;
    QString m_error;
    bool m_transportUsable = false;
    bool m_reported = false;

    QByteArray m_sendKey;
    QByteArray m_sendNoncePrefix;
    QByteArray m_receiveKey;
    QByteArray m_receiveNoncePrefix;
    quint64 m_sendCounter = 0;
    quint64 m_receiveCounter = 0;
};

/**
 * The desktop half of the Hisn LAN sync protocol.
 *
 * Listens on an ephemeral port on every interface and advertises itself through the QR payload in
 * pairingPayload(). One connection is served at a time; accepting is paused for the duration of a
 * session and resumed afterwards. The database merge is performed here, on the GUI thread, because
 * Database and the models attached to it are not thread safe.
 */
class SyncServer : public QTcpServer
{
    Q_OBJECT

public:
    explicit SyncServer(QObject* parent = nullptr);
    ~SyncServer() override;

    bool start(const QSharedPointer<Database>& database, QString* errorMessage = nullptr);
    void stop();

    QString address() const;
    QString deviceName() const;

    /** The single line of JSON that goes into the QR code. */
    QByteArray pairingPayload() const;

signals:
    void clientConnected(QString deviceName);
    void pairingSucceeded(QString deviceName);
    void syncProgress(qint64 done, qint64 total);
    void syncFinished(int added, int updated, int deleted);
    void syncFailed(QString reason);

protected:
    void incomingConnection(qintptr socketDescriptor) override;

private slots:
    void handleClientConnected(QString peerName);
    void handleMergeRequest();
    void handleSessionEnded();

private:
    bool loadStaticKey(QString* errorMessage);
    void loadPairingSecret();
    bool mergeDatabase(const QByteArray& incoming,
                       QByteArray& merged,
                       int& added,
                       int& updated,
                       int& deleted,
                       QString& error);

    static QString bestLanAddress();
    static QString localDeviceName();

    QSharedPointer<Database> m_database;
    QByteArray m_staticPrivateKey;
    QByteArray m_staticPublicKey;
    QByteArray m_pairingSecret;
    bool m_pairingSecretStored = false;
    QString m_address;
    QString m_deviceName;

    QSharedPointer<SyncMergeChannel> m_channel;
    QPointer<QThread> m_thread;
};

#endif // HISN_SYNCSERVER_H
