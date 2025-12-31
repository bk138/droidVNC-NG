/*
 * DroidVNC-NG NetIfDataDefaultNameDecorator, a concrete decorator implementation for NetIfData.
 * Given a NetIfData instance, this decorator shows the raw name of the referred NIC with a slight friendlier name for the "any" and "loopback" ones.
 *
 * Author: elluisian <elluisian@yandex.com>
 *
 * Copyright (C) 2024 Christian Beier (info@christianbeier.net>).
 *
 * You can redistribute and/or modify this program under the terms of the
 * GNU General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place Suite 330, Boston, MA 02111-1307, USA.
 */
package net.christianbeier.droidvnc_ng;


import android.content.Context;


import net.christianbeier.droidvnc_ng.ifaceutils.NetIfData;
import net.christianbeier.droidvnc_ng.ifaceutils.NetIfDataDecorator;



public class NetIfDataDefaultNameDecorator extends NetIfDataDecorator {
    public NetIfDataDefaultNameDecorator(NetIfData decorated, Context context) {
        super(decorated, context);
    }


    public String getName() {
        String raw = this.decorated.getName();
        String display = null;

        String optionId = this.decorated.getOptionId();
        if (optionId.equals(NetIfData.ANY_OPTION_ID)) {
            display = this.mContext.getResources().getString(R.string.main_activity_settings_listenif_spin_any);
            raw = "0.0.0.0";

        } else if (optionId.equals(NetIfData.LOOPBACK_OPTION_ID)) {
            display = "Loopback";

        }

        return display == null ? raw : String.format("%s (%s)", display, raw);
    }
}