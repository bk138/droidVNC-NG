/*
 * DroidVNC-NG NetIfDataDecorator, generic decorator pattern for NetIfData instances.
 * With this, it is possible to customize the name of an interface (this can be useful to offer user friendly names).
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
import android.content.res.Resources;
import android.net.Network;

import java.net.NetworkInterface;


public abstract class NetIfDataDecorator implements INetIfData {
    protected NetIfData decorated;
    protected Context mContext;
    protected Resources mResources;


    public NetIfDataDecorator(NetIfData decorated) {
        this(decorated, null);
    }


    public NetIfDataDecorator(NetIfData decorated, Context context) {
        this.decorated = decorated;

        if (context != null) {
            this.mContext = context;
            this.mResources = this.mContext.getResources();
        }
    }


    public abstract String getName();


    public final String getOptionId() {
        return this.decorated.getOptionId();
    }

    public final boolean isAny() {
        return this.decorated.isAny();
    }

    public final boolean isLoopback() {
        return this.decorated.isLoopback();
    }

    public final NetworkInterface getNetworkInterface() {
        return this.decorated.getNetworkInterface();
    }

    public final Network getNetwork() {
        return this.decorated.getNetwork();
    }

    public final NetIfData getWrapped() {
        return this.decorated;
    }
}