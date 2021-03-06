/*
 * Copyright (c) 2011 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 */

package ru.orangesoftware.financisto.activity;

import android.app.TabActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TabHost;
import ru.orangesoftware.financisto.R;
import ru.orangesoftware.financisto.utils.Utils;

/**
 * Created by IntelliJ IDEA.
 * User: Denis Solonenko
 * Date: 3/24/11 10:20 PM
 */
public class AboutActivity extends TabActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState ) {
        super.onCreate(savedInstanceState);
        setTitle("Financisto ("+getAppVersion(this)+")");

        addTab("about", R.string.about);
        addTab("whatsnew", R.string.whats_new);
        addTab("gpl-2.0-standalone", R.string.license);
    }

    private void addTab(String name, int titleId) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra(WebViewActivity.FILENAME, name);
        TabHost tabHost = getTabHost();
        tabHost.addTab(tabHost.newTabSpec(name)
                .setIndicator(getString(titleId), getResources().getDrawable(R.drawable.ic_tab_about))
                .setContent(intent));
    }

    public static String getAppVersion(Context context) {
        try {
            PackageInfo info = Utils.getPackageInfo(context);
            return "v. "+info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

}
