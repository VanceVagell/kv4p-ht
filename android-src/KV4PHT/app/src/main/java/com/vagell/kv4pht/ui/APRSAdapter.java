/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.ui;

import android.content.Intent;
import android.icu.util.LocaleData;
import android.icu.util.ULocale;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.text.util.LocalePreferences;
import androidx.recyclerview.widget.RecyclerView;

import com.vagell.kv4pht.R;
import io.github.dkaukov.aprs.AprsEvent;
import com.vagell.kv4pht.data.AprsFeedRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class APRSAdapter extends RecyclerView.Adapter<APRSAdapter.APRSViewHolder> {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("h:mm a MMM d", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());
    public List<AprsFeedRow> aprsFeed;

    static DeliveryStatusStyle deliveryStatusStyle(int deliveryState) {
        switch (deliveryState) {
            case AprsEvent.DELIVERY_PENDING:
                return new DeliveryStatusStyle(R.drawable.ic_pending,
                    R.string.aprs_delivery_pending, R.color.primary_deselected);
            case AprsEvent.DELIVERY_DELIVERED:
                return new DeliveryStatusStyle(R.drawable.ic_check,
                    R.string.aprs_delivery_delivered, R.color.primary);
            case AprsEvent.DELIVERY_REJECTED:
                return new DeliveryStatusStyle(R.drawable.ic_rejected,
                    R.string.aprs_delivery_rejected, R.color.accent);
            case AprsEvent.DELIVERY_FAILED:
                return new DeliveryStatusStyle(R.drawable.ic_failed,
                    R.string.aprs_delivery_failed, R.color.accent);
            case AprsEvent.DELIVERY_NONE:
            default:
                return null;
        }
    }

    static boolean showCommentInFeed(int eventType) {
        return eventType != AprsEvent.POSITION_TYPE;
    }

    static String mapLabel(AprsEvent event) {
        if (event.getType() == AprsEvent.OBJECT_TYPE) {
            return AprsObjectSummary.from(event.getObjectName(), event.getComment()).mapLabel;
        }
        String name = trimmed(event.getFromCallsign());
        String description = trimmed(event.getComment());
        if (name.isEmpty()) return description;
        if (description.isEmpty()) return name;
        return name + ": " + description;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    static double fahrenheitToCelsius(double fahrenheit) {
        return (fahrenheit - 32.0) * 5.0 / 9.0;
    }

    static double fahrenheitToKelvin(double fahrenheit) {
        return fahrenheitToCelsius(fahrenheit) + 273.15;
    }

    static double milesPerHourToKilometresPerHour(double milesPerHour) {
        return milesPerHour * 1.609344;
    }

    static boolean usesMilesPerHour(Locale locale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocaleData.MeasurementSystem system = LocaleData.getMeasurementSystem(
                ULocale.forLocale(locale));
            return system == LocaleData.MeasurementSystem.US
                || system == LocaleData.MeasurementSystem.UK;
        }
        String country = locale.getCountry();
        return "US".equals(country) || "GB".equals(country)
            || "LR".equals(country) || "MM".equals(country);
    }

    static final class DeliveryStatusStyle {
        final int drawable;
        final int description;
        final int color;

        DeliveryStatusStyle(int drawable, int description, int color) {
            this.drawable = drawable;
            this.description = description;
            this.color = color;
        }
    }

    public APRSAdapter() {
        this.aprsFeed = new ArrayList<>();
    }

    @NonNull
    @Override
    public APRSViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = null;

        switch (viewType) {
            case AprsEvent.MESSAGE_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_message, parent, false);
                break;
            case AprsEvent.OBJECT_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_object, parent, false);
                break;
            case AprsEvent.POSITION_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_position, parent, false);
                break;
            case AprsEvent.WEATHER_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_weather, parent, false);
                break;
            case AprsEvent.STATUS_TYPE:
            case AprsEvent.STATION_CAPABILITIES_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_status, parent, false);
                break;
            case AprsEvent.UNKNOWN_TYPE:
            default:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_unknown, parent, false);
        }

        return new APRSViewHolder(itemView);
    }

    public void setAprsFeed(List<AprsFeedRow> aprsFeed) {
        this.aprsFeed = aprsFeed;
    }

    public String getFeedKey(int position) {
        return aprsFeed != null && position >= 0 && position < aprsFeed.size()
            ? aprsFeed.get(position).feedKey : null;
    }

    public int findFeedPosition(String feedKey) {
        if (aprsFeed != null && feedKey != null) {
            for (int i = 0; i < aprsFeed.size(); i++) {
                if (feedKey.equals(aprsFeed.get(i).feedKey)) return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemViewType(int position) {
        return aprsFeed.get(position).event.getType();
    }

    @Override
    public void onBindViewHolder(@NonNull APRSViewHolder holder, int position) {
        final AprsEvent aprsEvent = aprsFeed.get(position).event;

        // Some default values any message type can have
        holder.setFromCallsign(aprsEvent.getFromCallsign());
        holder.setTimestamp(aprsEvent.getFirstSeenMs());
        holder.setComment(showCommentInFeed(aprsEvent.getType()) ? aprsEvent.getComment() : null);
        holder.setPositionLat(aprsEvent.getPositionLat());
        holder.setPositionLong(aprsEvent.getPositionLong());
        holder.setDigipeated(aprsEvent.isDigipeated());

        // Specialized values
        switch (aprsEvent.getType()) {
            case AprsEvent.WEATHER_TYPE:
                holder.setTemperature(aprsEvent.getTemperature());
                holder.setHumidity(aprsEvent.getHumidity());
                holder.setPressure(aprsEvent.getPressure());
                holder.setRain(aprsEvent.getRain());
                holder.setSnow(aprsEvent.getSnow());
                holder.setWindForce(aprsEvent.getWindForce());
                holder.setWindDir(aprsEvent.getWindDirection());
                break;
            case AprsEvent.MESSAGE_TYPE:
                holder.setToCallsign(aprsEvent.getToCallsign());
                holder.setMsgBody(aprsEvent.getBody());
                holder.setDeliveryState(aprsEvent.getDeliveryState());
                break;
            case AprsEvent.OBJECT_TYPE:
                holder.setObjName(aprsEvent.getObjectName());
                holder.setComment(AprsObjectSummary.from(
                    aprsEvent.getObjectName(), aprsEvent.getComment()).cardText);
                break;
            case AprsEvent.POSITION_TYPE: // Can only have default values
            case AprsEvent.STATUS_TYPE: // Ditto
            case AprsEvent.STATION_CAPABILITIES_TYPE: // Ditto
            case AprsEvent.UNKNOWN_TYPE: // Ditto
                break;
            default:
                break;
        }
        holder.setRelayCallsign(aprsEvent.getRelayCallsign());

        // Handle taps on the message's position icon
        final View positionButton = holder.itemView.findViewById(R.id.senderPositionButton);
        positionButton.setOnClickListener(v -> {
            String coordinates = aprsEvent.getPositionLat() + "," + aprsEvent.getPositionLong();
            String label = mapLabel(aprsEvent);
            String mapQuery = label.isEmpty() ? coordinates : coordinates + " (" + label + ")";
            String geoUri = "geo:" + coordinates + "?q=" + Uri.encode(mapQuery);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return aprsFeed.size();
    }

    static class APRSViewHolder extends RecyclerView.ViewHolder {
        TextView textViewFromCallsign;
        TextView textViewTimestamp;
        TextView textViewComment;
        View senderPositionButton;
        TextView textViewTemperature;
        TextView textViewTemperatureUnit;
        TextView textViewHumidity;
        TextView textViewPressure;
        TextView textViewRain;
        TextView textViewSnow;
        TextView textViewWindForce;
        TextView textViewWindUnit;
        TextView textViewWindDir;
        TextView textViewToCallsign;
        TextView textViewMsgBody;
        ImageView deliveryStatusIcon;
        TextView textViewObjName;
        TextView textViewRelayCallsign;
        TextView textViewRelayViaLabel;
        TextView textViewDigipeated;

        public APRSViewHolder(@NonNull View itemView) {
            super(itemView);

            // Try to retrieve references to possible fields we can set.
            // Some will be null, depending on the type of this APRS message.
            textViewFromCallsign = itemView.findViewById(R.id.fromCallsign);
            textViewTimestamp = itemView.findViewById(R.id.timestamp);
            textViewComment = itemView.findViewById(R.id.comment);
            senderPositionButton = itemView.findViewById(R.id.senderPositionButton);
            textViewTemperature = itemView.findViewById(R.id.temperature);
            textViewTemperatureUnit = itemView.findViewById(R.id.temperatureUnit);
            textViewHumidity = itemView.findViewById(R.id.humidity);
            textViewPressure = itemView.findViewById(R.id.pressure);
            textViewRain = itemView.findViewById(R.id.rain);
            textViewSnow = itemView.findViewById(R.id.snow);
            textViewWindForce = itemView.findViewById(R.id.wind);
            textViewWindUnit = itemView.findViewById(R.id.windUnit);
            textViewWindDir = itemView.findViewById(R.id.windDirection);
            textViewToCallsign = itemView.findViewById(R.id.toCallsign);
            textViewMsgBody = itemView.findViewById(R.id.messageBody);
            deliveryStatusIcon = itemView.findViewById(R.id.messageDeliveryStatus);
            textViewObjName = itemView.findViewById(R.id.objName);
            textViewRelayCallsign = itemView.findViewById(R.id.relayCallsign);
            textViewRelayViaLabel = itemView.findViewById(R.id.relayViaLabel);
            textViewDigipeated = itemView.findViewById(R.id.digipeatedIndicator);
        }

        public void setFromCallsign(String fromCallsign) {
            if (null == textViewFromCallsign || null == fromCallsign) {
                return;
            }
            textViewFromCallsign.setText(fromCallsign);
        }

        public void setTimestamp(long timestamp) {
            if (null == textViewTimestamp) {
                return;
            }
            textViewTimestamp.setText(TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp)));
        }

        public void setComment(String comment) {
            if (null == textViewComment) {
                return;
            }
            if (null == comment || comment.trim().isEmpty()) {
                itemView.findViewById(R.id.commentHolder).setVisibility(View.GONE);
            } else {
                itemView.findViewById(R.id.commentHolder).setVisibility(View.VISIBLE);
                textViewComment.setText(comment);
            }
        }

        private void setHasPosition(boolean hasPosition) {
            if (null == senderPositionButton) {
                return;
            }
            senderPositionButton.setVisibility(hasPosition ? View.VISIBLE : View.GONE);
        }

        public void setPositionLat(double posLat) {
            setHasPosition(posLat != 0);
        }

        public void setPositionLong(double posLong) {
            setHasPosition(posLong != 0);
        }

        public void setTemperature(double temperature) {
            if (null == textViewTemperature) {
                return;
            }
            Locale locale = displayLocale();
            String temperatureUnit = LocalePreferences.getTemperatureUnit(locale);
            double displayTemperature = temperature;
            String unit = "°F";
            if (LocalePreferences.TemperatureUnit.CELSIUS.equals(temperatureUnit)) {
                displayTemperature = fahrenheitToCelsius(temperature);
                unit = "°C";
            } else if (LocalePreferences.TemperatureUnit.KELVIN.equals(temperatureUnit)) {
                displayTemperature = fahrenheitToKelvin(temperature);
                unit = "K";
            }
            textViewTemperature.setText(String.format(locale, "%.1f", displayTemperature));
            if (textViewTemperatureUnit != null) textViewTemperatureUnit.setText(unit);
        }

        public void setHumidity(double humidity) {
            if (null == textViewHumidity) {
                return;
            }
            textViewHumidity.setText(String.format(Locale.US, "%.1f", humidity));
        }

        public void setPressure(double pressure) {
            if (null == textViewPressure) {
                return;
            }
            textViewPressure.setText(String.format(Locale.US, "%.1f", (pressure / 10f)));
        }

        public void setRain(double rain) {
            if (null == textViewRain) {
                return;
            }
            textViewRain.setText(String.format(Locale.US, "%.1f", rain));
        }

        public void setSnow(double snow) {
            if (null == textViewSnow) {
                return;
            }
            textViewSnow.setText(String.format(Locale.US, "%.1f", snow));
        }

        public void setWindForce(int windForce) {
            if (null == textViewWindForce) {
                return;
            }
            Locale locale = displayLocale();
            boolean useMilesPerHour = usesMilesPerHour(locale);
            double displaySpeed = useMilesPerHour ? windForce
                : milesPerHourToKilometresPerHour(windForce);
            textViewWindForce.setText(String.format(locale, "%.0f", displaySpeed));
            if (textViewWindUnit != null) {
                textViewWindUnit.setText(useMilesPerHour ? "mph" : "km/h");
            }
        }

        private Locale displayLocale() {
            if (!itemView.getResources().getConfiguration().getLocales().isEmpty()) {
                return itemView.getResources().getConfiguration().getLocales().get(0);
            }
            return Locale.getDefault();
        }

        public void setWindDir(String windDir) {
            if (null == textViewWindDir || null == windDir) {
                return;
            }
            textViewWindDir.setText(windDir);
        }

        public void setToCallsign(String toCallsign) {
            if (null == textViewToCallsign || null == toCallsign) {
                return;
            }
            textViewToCallsign.setText(toCallsign);
        }

        public void setMsgBody(String msgBody) {
            if (null == textViewMsgBody || null == msgBody) {
                return;
            }
            textViewMsgBody.setText(msgBody);
        }

        public void setDeliveryState(int deliveryState) {
            if (deliveryStatusIcon == null) return;
            DeliveryStatusStyle style = deliveryStatusStyle(deliveryState);
            if (style == null) {
                deliveryStatusIcon.setVisibility(View.GONE);
                deliveryStatusIcon.setContentDescription(null);
                return;
            }
            deliveryStatusIcon.setImageResource(style.drawable);
            deliveryStatusIcon.setColorFilter(
                ContextCompat.getColor(itemView.getContext(), style.color));
            deliveryStatusIcon.setContentDescription(
                itemView.getContext().getString(style.description));
            deliveryStatusIcon.setVisibility(View.VISIBLE);
        }

        public void setObjName(String objName) {
            if (null == textViewObjName || null == objName) {
                return;
            }
            textViewObjName.setText(objName);
        }

        public void setRelayCallsign(String relayCallsign) {
            // Relay callsigns are intentionally not displayed: they make the status line hard to read.
        }

        public void setDigipeated(boolean digipeated) {
            if (textViewDigipeated != null) {
                textViewDigipeated.setVisibility(digipeated ? View.VISIBLE : View.GONE);
            }
        }

    }
}
