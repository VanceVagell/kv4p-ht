package com.vagell.kv4pht.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.radio.RadioDeviceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Renders the USB and Bluetooth rows on the Connect radio screen. */
public class ConnectRadioAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_DEVICE = 1;
    private static final int TYPE_STATUS = 2;

    public interface Listener {
        void onPair(RadioDeviceInfo device);

        void onConnect(RadioDeviceInfo device);

        void onDisconnect(RadioDeviceInfo device);

        void onForget(RadioDeviceInfo device);

        void onShowFirmwareInfo(RadioDeviceInfo device);
    }

    public static final class Row {
        private final RadioDeviceInfo device;
        private final String statusText;

        private Row(RadioDeviceInfo device, String statusText) {
            this.device = device;
            this.statusText = statusText;
        }

        public static Row device(RadioDeviceInfo device) {
            return new Row(device, null);
        }

        public static Row status(String text) {
            return new Row(null, text);
        }

        public RadioDeviceInfo getDevice() {
            return device;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Row)) {
                return false;
            }
            Row that = (Row) other;
            return Objects.equals(device, that.device)
                    && Objects.equals(statusText, that.statusText);
        }

        @Override
        public int hashCode() {
            return Objects.hash(device, statusText);
        }
    }

    private final Listener listener;
    private List<Row> rows = Collections.emptyList();

    public ConnectRadioAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Row> newRows) {
        List<Row> next = newRows == null ? Collections.emptyList() : new ArrayList<>(newRows);
        if (rows.equals(next)) {
            // Scanning can update often; rebinding identical rows interrupts in-flight taps on the
            // per-row overflow button.
            return;
        }
        rows = next;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).device == null ? TYPE_STATUS : TYPE_DEVICE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_STATUS) {
            return new StatusHolder(inflater.inflate(R.layout.item_radio_status, parent, false));
        }
        return new DeviceHolder(inflater.inflate(R.layout.item_radio_device, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof StatusHolder) {
            ((StatusHolder) holder).text.setText(row.statusText);
        } else if (holder instanceof DeviceHolder) {
            bindDevice((DeviceHolder) holder, row.device);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private void bindDevice(DeviceHolder holder, RadioDeviceInfo device) {
        Context context = holder.itemView.getContext();
        holder.name.setText(device.displayNameWithTransport());
        // The active radio stands out with full-opacity text and a medium weight; every other row
        // keeps the existing dimmed treatment.
        holder.name.setTextAppearance(context, device.isConnected()
                ? R.style.ConnectRadioDeviceNameConnected
                : R.style.ConnectRadioDeviceName);
        if (device.getDetail().isEmpty()) {
            holder.detail.setVisibility(View.GONE);
        } else {
            holder.detail.setVisibility(View.VISIBLE);
            holder.detail.setText(device.getDetail());
        }

        holder.action.setOnClickListener(null);
        holder.info.setOnClickListener(null);
        holder.menu.setOnClickListener(null);
        // Only the explicit controls are tappable. Tapping the row/name must never trigger an
        // action, especially not destructive ones like "Forget".
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);

        boolean oldFirmware = device.isFirmwareUpdateRequired();
        holder.info.setVisibility(oldFirmware ? View.VISIBLE : View.GONE);
        holder.action.setTextColor(ContextCompat.getColor(context, R.color.primary));
        holder.action.setEnabled(true);
        holder.action.setVisibility(View.VISIBLE);

        if (device.isConnecting()) {
            holder.action.setText(R.string.connect_radio_connecting);
            holder.action.setTextColor(ContextCompat.getColor(context,
                    R.color.primary_half));
            holder.action.setEnabled(false);
            holder.menu.setVisibility(View.GONE);
        } else if (device.getKind() == RadioDeviceInfo.Kind.USB) {
            holder.action.setText(device.isConnected()
                    ? R.string.connect_radio_plugged_in : R.string.connect_radio_connect);
            if (device.isConnected()) {
                holder.action.setTextColor(ContextCompat.getColor(context,
                        R.color.primary_half));
                holder.action.setEnabled(false);
            } else {
                holder.action.setOnClickListener(view -> listener.onConnect(device));
            }
            holder.menu.setVisibility(View.GONE);
        } else if (oldFirmware) {
            holder.action.setText(R.string.connect_radio_old_firmware);
            holder.action.setTextColor(ContextCompat.getColor(context,
                    R.color.primary_half));
            holder.action.setOnClickListener(view -> listener.onShowFirmwareInfo(device));
            holder.info.setOnClickListener(view -> listener.onShowFirmwareInfo(device));
            boolean hasMenuActions = device.isConnected() || device.isPaired();
            holder.menu.setVisibility(hasMenuActions ? View.VISIBLE : View.GONE);
            holder.menu.setOnClickListener(hasMenuActions
                    ? view -> showMenu(view, device) : null);
        } else if (device.isConnected()) {
            holder.action.setText(R.string.connect_radio_disconnect);
            holder.action.setOnClickListener(view -> listener.onDisconnect(device));
            holder.menu.setVisibility(View.GONE);
        } else if (device.isPaired() && device.isVisible()) {
            holder.action.setText(R.string.connect_radio_connect);
            holder.action.setOnClickListener(view -> listener.onConnect(device));
            holder.menu.setVisibility(View.VISIBLE);
            holder.menu.setOnClickListener(view -> showMenu(view, device));
        } else if (device.isPaired()) {
            holder.action.setText(R.string.connect_radio_forget);
            holder.action.setOnClickListener(view -> listener.onForget(device));
            holder.menu.setVisibility(View.GONE);
        } else {
            holder.action.setText(R.string.connect_radio_pair);
            holder.action.setOnClickListener(view -> listener.onPair(device));
            holder.menu.setVisibility(View.GONE);
        }
    }

    private void showMenu(View anchor, RadioDeviceInfo device) {
        Context themedContext = new ContextThemeWrapper(anchor.getContext(),
                R.style.Custom_PopupMenu);
        PopupMenu menu = new PopupMenu(themedContext, anchor);
        if (device.isConnected()) {
            menu.getMenu().add(0, 1, 0, R.string.connect_radio_disconnect);
        }
        if (device.isPaired()) {
            menu.getMenu().add(0, 2, 1, R.string.connect_radio_forget);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                listener.onDisconnect(device);
            } else if (item.getItemId() == 2) {
                listener.onForget(device);
            }
            return true;
        });
        menu.show();
    }

    private static final class DeviceHolder extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView detail;
        private final TextView action;
        private final ImageView info;
        private final ImageButton menu;

        private DeviceHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.deviceName);
            detail = itemView.findViewById(R.id.deviceDetail);
            action = itemView.findViewById(R.id.deviceAction);
            info = itemView.findViewById(R.id.deviceInfo);
            menu = itemView.findViewById(R.id.deviceMenu);
        }
    }

    private static final class StatusHolder extends RecyclerView.ViewHolder {
        private final TextView text;

        private StatusHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.statusText);
        }
    }
}