/*
 * DroidVNC-NG ListenIfAdapter, this is the adapter concrete implementation that is set to the Spinner in the main activity.
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
package net.christianbeier.droidvnc_ng;


import android.content.res.Resources;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Spinner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;


import java.util.ArrayList;
import java.util.List;


import net.christianbeier.droidvnc_ng.ifaceutils.NetIfData;
import net.christianbeier.droidvnc_ng.ifaceutils.NetIfDataDecorator;
import net.christianbeier.droidvnc_ng.ifaceutils.NetworkInterfaceTester;



public class ListenIfAdapter extends ArrayAdapter<NetIfData> implements NetworkInterfaceTester.OnNetworkStateChangedListener {
    // This adapter uses the ViewHolder pattern
    private static class ViewHolder {
        public TextView txtLabel;
    }

    // Data to be shown with the adapter
    private List<NetIfDataDecorator> data;
    private int dataSize;


    // Some context data for "easy retrieval"
    private Context mContext;
    private LayoutInflater mInflater;


    // UI related
    private Handler handler;



    public ListenIfAdapter(Context context) {
        super(context, R.layout.spinner_row, R.id.spinner_text);

        this.mContext = context;
        this.mInflater = LayoutInflater.from(this.mContext);
        this.handler = new Handler(Looper.getMainLooper());

        NetworkInterfaceTester nit = NetworkInterfaceTester.getInstance(context);
        nit.addOnNetworkStateChangedListener(this);
        this.onNetworkStateChanged(nit);
    }



    public int getItemPositionByOptionId(String optionId) {
        int i = 0;
        for (NetIfDataDecorator nid : this.data) {
            if (nid.getOptionId().equals(optionId)) {
                return i;
            }
            i++;
        }

        return 1; // return loopback position as default
    }



    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return this.handleViewRecreation(position, convertView, parent);
    }


    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return this.handleViewRecreation(position, convertView, parent);
    }


    private View handleViewRecreation(int position, View convertView, ViewGroup parent) {
        if (convertView == null) { // Check if view must be recreated using the famous ViewHolder pattern
            convertView = this.mInflater.inflate(R.layout.spinner_row, parent, false);

            ViewHolder vh = new ViewHolder();
            vh.txtLabel = convertView.findViewById(R.id.spinner_text);
            convertView.setTag(vh);
        }

        ViewHolder vh = (ViewHolder)convertView.getTag();
        vh.txtLabel.setText(this.data.get(position).getName());

        return convertView;
    }



    @Override
    public NetIfData getItem(int position) {
        if (0 <= position && position < this.getCount()) {
            return this.data.get(position).getWrapped();
        }
        return null;
    }



    @Override
    public int getCount() {
        return this.dataSize;
    }



    public void onNetworkStateChanged(NetworkInterfaceTester nit) {
        List<NetIfData> avNetIfs = nit.getAvailableNetIfs();

        this.data = new ArrayList<>();
        for (NetIfData nid : avNetIfs) {
            this.data.add(new NetIfDataDefaultNameDecorator(nid, this.mContext));
        }
        this.dataSize = this.data.size();


        // update Spinner
        this.handler.post(new Runnable() {
            public void run() {
                ListenIfAdapter.this.notifyDataSetChanged();
            }
        });
    }
}