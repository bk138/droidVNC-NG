/*
 * DroidVNC-NG main activity.
 *
 * Author: Christian Beier <info@christianbeier.net
 *
 * Copyright (C) 2020 Kitchen Armor.
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

import android.Manifest;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private Button mButtonToggle;
    private boolean mIsMainServiceRunning;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButtonToggle = (Button) findViewById(R.id.toggle);
        mButtonToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                Intent intent = new Intent(MainActivity.this, MainService.class);
                if(mIsMainServiceRunning) {
                    intent.setAction(MainService.ACTION_STOP);
                    mButtonToggle.setText(R.string.start);
                    mIsMainServiceRunning = false;
                }
                else {
                    intent.setAction(MainService.ACTION_START);
                    mButtonToggle.setText(R.string.stop);
                    mIsMainServiceRunning = true;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }

            }
        });


    }

    @Override
    protected void onResume() {
        super.onResume();

        /*
            Update Toggle button.
         */
        mIsMainServiceRunning = false;
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MainService.class.getName().equals(service.service.getClassName())) {
                mIsMainServiceRunning = true;
                break;
            }
        }
        if (mIsMainServiceRunning)
            mButtonToggle.setText(R.string.stop);
        else
            mButtonToggle.setText(R.string.start);


        /*
            Update Input permission display.
         */
        TextView inputStatus = findViewById(R.id.permission_status_input);
        if(InputService.isEnabled()) {
            inputStatus.setText(R.string.main_activity_granted);
            inputStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            inputStatus.setText(R.string.main_activity_denied);
            inputStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }


        /*
            Update File Access permission display.
         */
        TextView fileAccessStatus = findViewById(R.id.permission_status_file_access);
        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            fileAccessStatus.setText(R.string.main_activity_granted);
            fileAccessStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            fileAccessStatus.setText(R.string.main_activity_denied);
            fileAccessStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }


    }


}