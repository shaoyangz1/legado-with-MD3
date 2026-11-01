package com.xunlei.downloadlib.parameter;

import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class GetTaskId {

    public long mTaskId;
    public File mSavePath;
    public String mFileName;
    public String mRealUrl;

    public GetTaskId(File savePath) {
        this.mSavePath = savePath;
    }

    public GetTaskId(String url, File savePath) {
        File file = new File(Uri.parse(url).getPath());
        saveFile(file, savePath);
        this.mFileName = file.getName();
        this.mSavePath = savePath;
        this.mRealUrl = url;
    }

    public GetTaskId(File savePath, String fileName, String realUrl) {
        this.mSavePath = savePath;
        this.mFileName = fileName;
        this.mRealUrl = realUrl;
    }

    private void saveFile(File file, File savePath) {
        try {
            copyFile(file, new File(savePath, file.getName()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void copyFile(File file, File dest) throws IOException {
        if (!file.isFile()) throw new IOException("Unable to read " + file);
        try (InputStream in = new FileInputStream(file);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }

    public long getTaskId() {
        return this.mTaskId;
    }

    public File getSavePath() {
        return mSavePath;
    }

    public String getFileName() {
        return mFileName;
    }

    public String getRealUrl() {
        return mRealUrl;
    }

    public File getSaveFile() {
        return new File(getSavePath(), getFileName());
    }
}
