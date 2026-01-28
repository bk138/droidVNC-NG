/*
 * DroidVNC-NG INetIfData, interface defining what is needed to properly gather all the data related to a particular NIC (Network interface card).
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


import android.net.Network;


import java.net.NetworkInterface;



public interface INetIfData {
    public static final String ANY_OPTION_ID = "any";
    public static final String ANY_OPTION_ID_ADDR = "0.0.0.0";
    public static final String LOOPBACK_OPTION_ID = "loopback";
    public static final String LOOPBACK_OPTION_ID_ADDR_1 = "localhost";
    public static final String LOOPBACK_OPTION_ID_ADDR_2 = "127.0.0.1";


    public String getName();
    public String getOptionId();
    public boolean isLoopback();
    public boolean isAny();

    public NetworkInterface getNetworkInterface();
    public Network getNetwork();
}