/*
 * DroidVNC-NG Concrete implementation of INetIfData.
 *
 * Author: elluisian <elluisian@yandex.com>
 *
 * Copyright (C) 2024 Christian Beier.
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
package net.christianbeier.droidvnc_ng.ifaceutils;


import android.net.Network;


import java.net.SocketException;
import java.net.NetworkInterface;
import java.util.ArrayList;



public class NetIfData implements INetIfData {
    private static NetIfData ANY_IF;

    private NetworkInterface nic;
    private Network net;
    private String name;

    private boolean enabled;
    private boolean anyFlag;
    private boolean loopbackFlag;




    private NetIfData() {
        this(null);
    }

    private NetIfData(NetworkInterface nic) {
        this(nic, null);
    }

    private NetIfData(NetworkInterface nic, Network net) {
        this.name = null;
        this.anyFlag = false;
        this.loopbackFlag = false;
        this.nic = nic;
        this.net = net;

        try {
            // Considering that when this.nic is null, it is highly probable that the referred interface is "any", consider it enabled
            this.enabled = (nic == null) ? true : nic.isUp();

        } catch(SocketException ex) {
            this.enabled = false;

        }

        if (nic == null) {
            this.name = NetIfData.ANY_OPTION_ID;
            this.anyFlag = true;

        } else {
            this.name = this.nic.getName();
            try {
                this.loopbackFlag = this.nic.isLoopback();

            } catch(SocketException ex) {
                this.loopbackFlag = false;

            }
        }
    }




    public String getName() {
        return this.name;
    }

    public String getOptionId() {
        if (this.anyFlag) {
            return NetIfData.ANY_OPTION_ID;

        } else if (this.loopbackFlag) {
            return NetIfData.LOOPBACK_OPTION_ID;

        }

        return this.getName();
    }

    public boolean isAny() {
        return this.anyFlag;
    }

    public boolean isLoopback() {
        return this.loopbackFlag;
    }

    public NetworkInterface getNetworkInterface() {
        return this.nic;
    }




    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Network getNetwork() {
        return this.net;
    }

    public void setNetwork(Network net) {
        this.net = net;
    }




    public static NetIfData getAny() {
        if (NetIfData.ANY_IF == null) {
            NetIfData.ANY_IF = new NetIfData();
        }
        return NetIfData.ANY_IF;
    }

    public static NetIfData getNetIf(NetworkInterface nic) {
        return new NetIfData(nic);
    }

    public static NetIfData getNetIf(NetworkInterface nic, Network net) {
        return new NetIfData(nic, net);
    }

    public static boolean isOptionIdAny(String optionId) {
        return (optionId.equals(NetIfData.ANY_OPTION_ID) ||
            optionId.equals(NetIfData.ANY_OPTION_ID_ADDR));
    }

    public static boolean isOptionIdLoopback(String optionId) {
        return (optionId.equals(NetIfData.LOOPBACK_OPTION_ID) ||
            optionId.equals(NetIfData.LOOPBACK_OPTION_ID_ADDR_1) ||
            optionId.equals(NetIfData.LOOPBACK_OPTION_ID_ADDR_2)
        );
    }
}