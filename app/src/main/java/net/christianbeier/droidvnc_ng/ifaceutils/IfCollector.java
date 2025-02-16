/*
 * DroidVNC-NG IfCollector is a collector of NetIfData instances, these represent available NICs (Network Interface Card).
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
package net.christianbeier.droidvnc_ng.ifaceutils;


import android.net.LinkProperties;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.content.Context;
import android.util.Log;


import java.net.SocketException;
import java.net.NetworkInterface;
import java.util.List;
import java.util.ArrayList;


import net.christianbeier.droidvnc_ng.Utils;



public class IfCollector {
    private static IfCollector COLL;

    private NetIfData any;
    private NetIfData loopback;
    private List<NetIfData> nics;
    private int nicsSize;



    public static synchronized IfCollector getInstance() {
        if (IfCollector.COLL == null) {
            IfCollector.COLL = new IfCollector();
        }

        return IfCollector.COLL;
    }


    private IfCollector() {
        this.any = NetIfData.getAny();

        List<NetworkInterface> ifs = Utils.getAvailableNICs();
        this.nics = new ArrayList<>();

        for (NetworkInterface inf : ifs) {
            NetIfData nid = NetIfData.getNetIf(inf);
            if (!nid.isLoopback()) {
                this.nics.add(nid);
            } else {
                this.loopback = nid;
            }
        }

        this.nicsSize = this.nics.size();
    }




    public int searchForNetIfByOptionId(String optionId) {
        int i = 0;
        boolean found = false;
        for (i = 0; !found && i < this.nicsSize; i++) {
            if (optionId.equals(this.nics.get(i).getOptionId())) {
                i--;
                found = true;
            }
        }

        return found ? i : -1;
    }


    public int searchForNetIfByNetwork(Network network) {
        int i = 0;
        boolean found = false;
        for (i = 0; !found && i < this.nicsSize; i++) {
            if (network.equals(this.nics.get(i).getNetwork())) {
                i--;
                found = true;
            }
        }

        return found ? i : -1;
    }



    public void addNetIf(NetworkInterface iface, Network network) {
        this.nics.add(NetIfData.getNetIf(iface, network));
        this.nicsSize++;
    }


    public List<NetIfData> getEnabledNetIfs() {
        List<NetIfData> ls = new ArrayList<>();

        for (int i = 0; i < this.nicsSize; i++) {
            NetIfData nid = this.nics.get(i);
            if (nid.isEnabled()) {
                ls.add(nid);
            }
        }

        return ls;
    }

    public NetIfData getAny() {
        return this.any;
    }

    public NetIfData getLoopback() {
        return this.loopback;
    }

    public NetIfData getNetIf(int i) {
        if (0 <= i && i < this.nicsSize) {
            return this.nics.get(i);
        }

        return null;
    }

    public int getSize() {
        return this.nicsSize;
    }
}