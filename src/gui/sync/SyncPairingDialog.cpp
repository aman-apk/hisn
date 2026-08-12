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

#include "SyncPairingDialog.h"

#include "gui/DatabaseWidget.h"
#include "gui/SquareSvgWidget.h"
#include "gui/styles/StateColorPalette.h"
#include "qrcode/QrCode.h"
#include "sync/SyncServer.h"

#include <QBuffer>
#include <QDialogButtonBox>
#include <QFont>
#include <QLabel>
#include <QProgressBar>
#include <QPushButton>
#include <QStackedWidget>
#include <QVBoxLayout>

SyncPairingDialog::SyncPairingDialog(DatabaseWidget* dbWidget, QWidget* parent)
    : QDialog(parent)
    , m_dbWidget(dbWidget)
    , m_server(new SyncServer(this))
    , m_instructions(new QLabel(this))
    , m_qrCode(nullptr)
    , m_addressLabel(new QLabel(this))
    , m_status(new QLabel(this))
    , m_progress(new QProgressBar(this))
    , m_buttonBox(new QDialogButtonBox(QDialogButtonBox::Close, this))
{
    setObjectName("syncPairingDialog");
    setWindowTitle(tr("المزامنة المحلية"));
    setAttribute(Qt::WA_DeleteOnClose);
    setWindowModality(Qt::WindowModality::ApplicationModal);

    buildLayout();

    connect(m_buttonBox, &QDialogButtonBox::rejected, this, &SyncPairingDialog::close);
    connect(m_server, &SyncServer::clientConnected, this, &SyncPairingDialog::showClientConnected);
    connect(m_server, &SyncServer::pairingSucceeded, this, &SyncPairingDialog::showPairingSucceeded);
    connect(m_server, &SyncServer::syncProgress, this, &SyncPairingDialog::showProgress);
    connect(m_server, &SyncServer::syncFinished, this, &SyncPairingDialog::showFinished);
    connect(m_server, &SyncServer::syncFailed, this, &SyncPairingDialog::showFailed);

    if (m_dbWidget) {
        // A database that locks or goes away mid-session must not stay reachable over the network.
        connect(m_dbWidget, &DatabaseWidget::databaseLocked, this, &SyncPairingDialog::close);
        connect(m_dbWidget, &QObject::destroyed, this, &SyncPairingDialog::close);
    }

    if (!m_dbWidget || m_dbWidget->isLocked() || !m_dbWidget->database()) {
        setStatus(tr("افتح قاعدة بيانات وأزل قفلها قبل بدء المزامنة."), true);
        return;
    }

    QString error;
    if (!m_server->start(m_dbWidget->database(), &error)) {
        setStatus(error, true);
        return;
    }

    showQrCode();
    m_addressLabel->setText(tr("العنوان: %1 — المنفذ: %2").arg(m_server->address()).arg(m_server->serverPort()));
    setStatus(tr("في انتظار الهاتف…"));
}

SyncPairingDialog::~SyncPairingDialog()
{
    m_server->stop();
}

void SyncPairingDialog::buildLayout()
{
    auto* layout = new QVBoxLayout(this);

    auto* heading = new QLabel(tr("المزامنة المحلية"), this);
    QFont headingFont = heading->font();
    headingFont.setBold(true);
    heading->setFont(headingFont);
    heading->setAlignment(Qt::AlignCenter);
    layout->addWidget(heading);

    m_instructions->setText(tr("افتح تطبيق حصن على هاتفك، اختر المزامنة المحلية، ثم امسح هذا الرمز. "
                               "لا بد أن يكون الجهازان على الشبكة اللاسلكية نفسها."));
    m_instructions->setWordWrap(true);
    m_instructions->setAlignment(Qt::AlignCenter);
    layout->addWidget(m_instructions);

    auto* qrContainer = new QStackedWidget(this);
    m_qrCode = new SquareSvgWidget(qrContainer);
    qrContainer->addWidget(m_qrCode);
    const auto minimumSize = static_cast<int>(logicalDpiX() * 2.5);
    m_qrCode->setMinimumSize(minimumSize, minimumSize);
    layout->addWidget(qrContainer);

    m_addressLabel->setAlignment(Qt::AlignCenter);
    m_addressLabel->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(m_addressLabel);

    m_progress->setRange(0, 100);
    m_progress->setValue(0);
    m_progress->hide();
    layout->addWidget(m_progress);

    m_status->setAlignment(Qt::AlignCenter);
    m_status->setWordWrap(true);
    layout->addWidget(m_status);

    m_buttonBox->button(QDialogButtonBox::Close)->setText(tr("إغلاق"));
    layout->addWidget(m_buttonBox);

    setLayout(layout);
}

void SyncPairingDialog::showQrCode()
{
    const auto payload = m_server->pairingPayload();
    if (payload.isEmpty()) {
        setStatus(tr("تعذر إنشاء رمز الاقتران."), true);
        return;
    }

    const QrCode code(payload, QrCode::Version::AUTO, QrCode::ErrorCorrectionLevel::MEDIUM);
    if (!code.isValid()) {
        setStatus(tr("تعذر إنشاء رمز الاقتران."), true);
        return;
    }

    QBuffer buffer;
    code.writeSvg(&buffer, logicalDpiX());
    m_qrCode->load(buffer.data());
}

void SyncPairingDialog::setStatus(const QString& text, bool isError)
{
    auto palette = m_status->palette();
    palette.setColor(m_status->foregroundRole(),
                     isError ? StateColorPalette().color(StateColorPalette::Error)
                             : QDialog::palette().color(foregroundRole()));
    m_status->setPalette(palette);
    m_status->setText(text);
}

void SyncPairingDialog::showClientConnected(const QString& deviceName)
{
    setStatus(tr("متصل بـ %1…").arg(deviceName));
}

void SyncPairingDialog::showPairingSucceeded(const QString& deviceName)
{
    m_progress->hide();
    setStatus(tr("تم الاقتران مع %1 بنجاح. يمكنك الآن المزامنة من الهاتف.").arg(deviceName));
}

void SyncPairingDialog::showProgress(qint64 done, qint64 total)
{
    if (total <= 0) {
        m_progress->hide();
        return;
    }
    m_progress->show();
    m_progress->setValue(static_cast<int>(done * 100 / total));
    setStatus(tr("جارٍ نقل قاعدة البيانات…"));
}

void SyncPairingDialog::showFinished(int added, int updated, int deleted)
{
    m_progress->setValue(100);
    setStatus(tr("اكتملت المزامنة: %1 مضاف، %2 محدَّث، %3 محذوف.").arg(added).arg(updated).arg(deleted));
}

void SyncPairingDialog::showFailed(const QString& reason)
{
    m_progress->hide();
    setStatus(tr("فشلت المزامنة: %1").arg(reason), true);
}

void SyncPairingDialog::closeEvent(QCloseEvent* event)
{
    m_server->stop();
    QDialog::closeEvent(event);
}
