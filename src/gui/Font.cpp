/*
 *  Copyright (C) 2017 KeePassXC Team <team@keepassxc.org>
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

#include "Font.h"

#include <QFontDatabase>
#include <QGuiApplication>

void Font::installApplicationFont()
{
    // keepassxc_gui is a static library, so the linker discards the generated resource object
    // unless something references it. This forces the font resource to register itself.
    Q_INIT_RESOURCE(fonts);

    // Every weight has to be registered: Qt will otherwise synthesise the missing ones, and a
    // synthesised bold smears the joins and diacritics of Arabic text.
    const QStringList fonts = {QStringLiteral(":/fonts/Almarai-Light.ttf"),
                               QStringLiteral(":/fonts/Almarai-Regular.ttf"),
                               QStringLiteral(":/fonts/Almarai-Bold.ttf"),
                               QStringLiteral(":/fonts/Almarai-ExtraBold.ttf")};

    QString family;
    for (const auto& path : fonts) {
        const int id = QFontDatabase::addApplicationFont(path);
        if (id < 0) {
            continue;
        }
        const auto families = QFontDatabase::applicationFontFamilies(id);
        if (family.isEmpty() && !families.isEmpty()) {
            family = families.first();
        }
    }

    if (family.isEmpty()) {
        // Nothing to do: the resource is missing, so keep whatever font the platform picked.
        return;
    }

    auto font = qApp->font();
    font.setFamily(family);
    qApp->setFont(font);
}

QFont Font::defaultFont()
{
    return qApp->font();
}

QFont Font::fixedFont()
{
    auto fixedFont = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    fixedFont.setPointSize(defaultFont().pointSize());

#ifdef Q_OS_WIN
    // try to use Consolas on Windows, because the default Courier New has too many similar characters
    auto consolasFont = QFontDatabase().font("Consolas", fixedFont.styleName(), defaultFont().pointSize());
    if (consolasFont.family().contains("consolas", Qt::CaseInsensitive)) {
        fixedFont = consolasFont;
        // Bump up the font size by one point to better match the default font on Windows
        fixedFont.setPointSize(defaultFont().pointSize() + 1);
    }
#endif
#ifdef Q_OS_MACOS
    // Qt doesn't choose a monospace font correctly on macOS
    fixedFont = QFontDatabase().font("Menlo", fixedFont.styleName(), defaultFont().pointSize());
    fixedFont.setPointSize(defaultFont().pointSize());
#endif
    return fixedFont;
}
