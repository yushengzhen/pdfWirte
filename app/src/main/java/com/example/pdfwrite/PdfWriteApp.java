package com.example.pdfwrite;

import android.app.Application;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public class PdfWriteApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PDFBoxResourceLoader.init(getApplicationContext());
    }
}
