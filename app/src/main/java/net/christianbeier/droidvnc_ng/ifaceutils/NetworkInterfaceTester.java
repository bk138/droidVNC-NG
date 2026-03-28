/*
 * DroidVNC-NG NetworkInterfaceTester, this is used by MainService/MainActivity used to detect whether network connections are lost or added.
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



public class NetworkInterfaceTester extends ConnectivityManager.NetworkCallback {
    public static final String TAG = "NetworkInterfaceTester";
    private static NetworkInterfaceTester INSTANCE;



    public synchronized static NetworkInterfaceTester getInstance(Context context) {
        if (NetworkInterfaceTester.INSTANCE == null) {
            NetworkInterfaceTester.INSTANCE = new NetworkInterfaceTester(context);
        }
        return NetworkInterfaceTester.INSTANCE;
    }



    public static interface OnNetworkStateChangedListener {
        public void onNetworkStateChanged(NetworkInterfaceTester nit);
    }



    private IfCollector ifCollector;
    private Context context;
    private ConnectivityManager manager;
    private List<OnNetworkStateChangedListener> listeners;



    private NetworkInterfaceTester(Context context) {
        this.ifCollector = IfCollector.getInstance();

        this.listeners = new ArrayList<>();
        this.manager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.manager.registerNetworkCallback(
            new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // This is needed, otherwise, VPN start/stop is not detected
                .build(),
            this
        );
    }



    public ArrayList<NetIfData> getAvailableNetIfs() {
        ArrayList<NetIfData> ls = new ArrayList<>();

        ls.add(this.ifCollector.getAny());
        ls.add(this.ifCollector.getLoopback());
        ls.addAll(this.ifCollector.getEnabledNetIfs());

        return ls;
    }




    @Override
    public void onAvailable(Network network) {
        super.onAvailable(network);
        int i = this.storeNetwork(network);

        if (i != -1) {
            NetIfData nid = this.ifCollector.getNetIf(i);
            nid.setEnabled(true);
            this.updateListener();
        }
    }


    @Override
    public void onLost(Network network) {
        super.onLost(network);
        int i = this.getFromNetwork(network);

        if (i != -1) {
            NetIfData nid = this.ifCollector.getNetIf(i);
            nid.setEnabled(false);
            this.updateListener();
        }
    }




    @Override
    public void onLosing(Network network, int maxMsToLive) {
        Log.d(TAG, "onLosing " + network);
    }

    @Override
    public void onBlockedStatusChanged(Network network, boolean blocked) {
        Log.d(TAG, "onBlockedStatusChagned " + network);
    }

    @Override
    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
        Log.d(TAG, "onLinkPropertiesChanged " + network);
    }




    /*
     * The idea is simple: during onLost, LinkProperties attributes are lost
     * inside Network, so, memorize a reference to the Network instance, this way,
     * we'll be able to understand what exact interface was lost.
     */
    private int storeNetwork(Network network) {
        LinkProperties prop = this.manager.getLinkProperties(network);
        NetworkInterface iface = null;
        try {
            iface = NetworkInterface.getByName(prop.getInterfaceName());

        } catch (SocketException ex) {
            Log.d(TAG, "Socket exception has occurred, return -1");
            return -1;
        }

        // No socket exception occurred, let's see if the interface is available
        int i = this.ifCollector.searchForNetIfByOptionId(iface.getName());
        if (i == -1) { // if not found, it means that it has been created recently, add to list
            this.ifCollector.addNetIf(iface, network);

        } else {
            // Otherwise, the interface exists, replace network
            NetIfData nid = this.ifCollector.getNetIf(i);
            nid.setNetwork(network);
        }

        Log.d(TAG, "Added network " + iface.getName());

        return i;
    }


    private int getFromNetwork(Network network) {
        int i = this.ifCollector.searchForNetIfByNetwork(network);

        if (i != -1) {
            Log.d(TAG, "Removed network " + this.ifCollector.getNetIf(i).getOptionId());
        } else {
            Log.d(TAG, "Network to remove not found");
        }

        return i;
    }




    public void addOnNetworkStateChangedListener(OnNetworkStateChangedListener onscl) {
        if (this.listeners.indexOf(onscl) == -1) {
            this.listeners.add(onscl);
        }
    }


    private void updateListener() {
        for (OnNetworkStateChangedListener onscl : this.listeners) {
            onscl.onNetworkStateChanged(this);
        }
    }


    public boolean isIfEnabled(String listenIf) {
        if (NetIfData.isOptionIdLoopback(listenIf) ||
            NetIfData.isOptionIdAny(listenIf)) {
            return true;
        }

        NetIfData loopback = this.ifCollector.getLoopback();
        if (loopback.getName().equals(listenIf)) {
            return true;
        }


        int i = this.ifCollector.searchForNetIfByOptionId(listenIf);
        if (i != -1) {
            NetIfData nid = this.ifCollector.getNetIf(i);
            return nid.isEnabled();
        }

        return false;
    }
}