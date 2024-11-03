package com.vagell.kv4pht.firmware.bearconsole;


public interface UploadSTM32CallBack {

    /*
     * Callback methods
     */
    void onPreUpload();
    void onUploading(int value);
    void onInfo(String value);
    void onPostUpload(boolean success);
    void onCancel();
    void onError(UploadSTM32Errors err);

}
