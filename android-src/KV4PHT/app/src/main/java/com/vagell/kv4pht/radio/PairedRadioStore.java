package com.vagell.kv4pht.radio;

import java.util.List;

/** Persistence for the set of Bluetooth radios the app may auto-connect to. */
public interface PairedRadioStore {
    List<PairedRadio> getAll();

    void saveAll(List<PairedRadio> radios);
}