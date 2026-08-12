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

#ifndef HISN_SYNCPAIRINGDIALOG_H
#define HISN_SYNCPAIRINGDIALOG_H

#include <QDialog>
#include <QPointer>

class DatabaseWidget;
class QDialogButtonBox;
class QLabel;
class QProgressBar;
class SquareSvgWidget;
class SyncServer;

/**
 * Shows the pairing QR code for the LAN sync server and reports what the phone is doing.
 *
 * The dialog owns the server for its lifetime: the listening socket is opened when the dialog is
 * constructed and closed again as soon as it is closed, so nothing keeps a port open in the
 * background.
 */
class SyncPairingDialog : public QDialog
{
    Q_OBJECT

public:
    explicit SyncPairingDialog(DatabaseWidget* dbWidget, QWidget* parent = nullptr);
    ~SyncPairingDialog() override;

protected:
    void closeEvent(QCloseEvent* event) override;

private slots:
    void showClientConnected(const QString& deviceName);
    void showPairingSucceeded(const QString& deviceName);
    void showProgress(qint64 done, qint64 total);
    void showFinished(int added, int updated, int deleted);
    void showFailed(const QString& reason);

private:
    void buildLayout();
    void showQrCode();
    void setStatus(const QString& text, bool isError = false);

    QPointer<DatabaseWidget> m_dbWidget;
    SyncServer* m_server;

    QLabel* m_instructions;
    SquareSvgWidget* m_qrCode;
    QLabel* m_addressLabel;
    QLabel* m_status;
    QProgressBar* m_progress;
    QDialogButtonBox* m_buttonBox;
};

#endif // HISN_SYNCPAIRINGDIALOG_H
