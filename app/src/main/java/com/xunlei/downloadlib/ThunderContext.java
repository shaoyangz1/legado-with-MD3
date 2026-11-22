package com.xunlei.downloadlib;

import android.content.Context;

/** 迅雷 SDK 的 Application Context 持有者，调用前必须先 init。 */
public final class ThunderContext {

    private static volatile Context appContext;

    private ThunderContext() {
    }

    public static void init(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        appContext = context.getApplicationContext();
    }

    static Context get() {
        Context context = appContext;
        if (context == null) throw new IllegalStateException("ThunderContext.init() not called");
        return context;
    }
}
