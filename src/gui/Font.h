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

#ifndef KEEPASSX_FONT_H
#define KEEPASSX_FONT_H

class QFont;

class Font
{
public:
    /**
     * Loads the bundled Almarai family and makes it the application font.
     *
     * Called once at startup, before any widget is built. Almarai is bundled rather than taken
     * from the system so the app reads identically on every machine — most Linux desktops ship no
     * Arabic UI face at all and fall back to something that renders the script poorly.
     */
    static void installApplicationFont();

    static QFont defaultFont();
    static QFont fixedFont();

private:
    Font() = default;
};

#endif // KEEPASSX_FONT_H
