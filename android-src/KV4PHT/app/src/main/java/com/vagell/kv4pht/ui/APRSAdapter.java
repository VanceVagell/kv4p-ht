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

import static androidx.core.content.ContextCompat.startActivity;

import android.content.Intent;
import android.icu.text.SimpleDateFormat;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.APRSMessage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class APRSAdapter extends RecyclerView.Adapter<APRSAdapter.APRSViewHolder> {
    public List<APRSMessage> aprsMessageList;

    public APRSAdapter() {
        this.aprsMessageList = new ArrayList<>();
    }

    @NonNull
    @Override
    public APRSViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = null;

        switch (viewType) {
            case APRSMessage.MESSAGE_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_message, parent, false);
                break;
            case APRSMessage.OBJECT_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_object, parent, false);
                break;
            case APRSMessage.POSITION_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_position, parent, false);
                break;
            case APRSMessage.WEATHER_TYPE:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_weather, parent, false);
                break;
            case APRSMessage.UNKNOWN_TYPE:
            default:
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.aprs_unknown, parent, false);
        }

        return new APRSViewHolder(itemView);
    }

    public void setAPRSMessageList(List<APRSMessage> aprsMessageList) {
        this.aprsMessageList = aprsMessageList;
    }

    @Override
    public int getItemViewType(int position) {
        return aprsMessageList.get(position).type;
    }

    @Override
    public void onBindViewHolder(@NonNull APRSViewHolder holder, int position) {
        final APRSMessage aprsMessage = aprsMessageList.get(position);

        // Some default values any message type can have
        holder.setFromCallsign(aprsMessage.fromCallsign);
        holder.setTimestamp(aprsMessage.timestamp);
        holder.setComment(aprsMessage.comment);
        holder.setPositionLat(aprsMessage.positionLat);
        holder.setPositionLong(aprsMessage.positionLong);

        // Specialized values
        switch (aprsMessage.type) {
            case APRSMessage.WEATHER_TYPE:
                holder.setTemperature(aprsMessage.temperature);
                holder.setHumidity(aprsMessage.humidity);
                holder.setPressure(aprsMessage.pressure);
                holder.setRain(aprsMessage.rain);
                holder.setSnow(aprsMessage.snow);
                holder.setWindForce(aprsMessage.windForce);
                holder.setWindDir(aprsMessage.windDir);
                break;
            case APRSMessage.MESSAGE_TYPE:
                holder.setToCallsign(aprsMessage.toCallsign);
                holder.setMsgBody(aprsMessage.msgBody);
                holder.setWasAcknowledged(aprsMessage.wasAcknowledged);
                break;
            case APRSMessage.OBJECT_TYPE:
                holder.setObjName(aprsMessage.objName);
                break;
            case APRSMessage.POSITION_TYPE: // Can only have default values
            case APRSMessage.UNKNOWN_TYPE: // Ditto
                break;
        }

        // Handle taps on the message's position icon
        final View positionButton = holder.itemView.findViewById(R.id.senderPositionButton);
        positionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Show this location on a map
                String geoUri = "geo:" + aprsMessage.positionLat + "," + aprsMessage.positionLong + "?q=" + aprsMessage.positionLat + "," + aprsMessage.positionLong;
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
                v.getContext().startActivity(intent);
            }
        });
    }

    @Override
    public int getItemCount() {
        return aprsMessageList.size();
    }

    static class APRSViewHolder extends RecyclerView.ViewHolder {
        TextView textViewFromCallsign;
        TextView textViewTimestamp;
        TextView textViewComment;
        View senderPositionButton;
        TextView textViewTemperature;
        TextView textViewHumidity;
        TextView textViewPressure;
        TextView textViewRain;
        TextView textViewSnow;
        TextView textViewWindForce;
        TextView textViewWindDir;
        TextView textViewToCallsign;
        TextView textViewMsgBody;
        View ackIcon;
        TextView textViewObjName;

        public APRSViewHolder(@NonNull View itemView) {
            super(itemView);

            // Try to retrieve references to possible fields we can set.
            // Some will be null, depending on the type of this APRS message.
            textViewFromCallsign = itemView.findViewById(R.id.fromCallsign);
            textViewTimestamp = itemView.findViewById(R.id.timestamp);
            textViewComment = itemView.findViewById(R.id.comment);
            senderPositionButton = itemView.findViewById(R.id.senderPositionButton);
            textViewTemperature = itemView.findViewById(R.id.temperature);
            textViewHumidity = itemView.findViewById(R.id.humidity);
            textViewPressure = itemView.findViewById(R.id.pressure);
            textViewRain = itemView.findViewById(R.id.rain);
            textViewSnow = itemView.findViewById(R.id.snow);
            textViewWindForce = itemView.findViewById(R.id.wind);
            textViewWindDir = itemView.findViewById(R.id.windDirection);
            textViewToCallsign = itemView.findViewById(R.id.toCallsign);
            textViewMsgBody = itemView.findViewById(R.id.messageBody);
            ackIcon = itemView.findViewById(R.id.msgAck);
            textViewObjName = itemView.findViewById(R.id.objName);
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
            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat sdf = new SimpleDateFormat("h:mm a MMM d", Locale.ENGLISH);
            textViewTimestamp.setText(sdf.format(new Date(timestamp * 1000)));
        }

        public void setComment(String comment) {
            if (null == textViewComment) {
                return;
            }
            if (null == comment || comment.trim().length() == 0) {
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
            setHasPosition(posLat != 0 ? true : false);
        }

        public void setPositionLong(double posLong) {
            setHasPosition(posLong != 0 ? true : false);
        }

        public void setTemperature(double temperature) {
            if (null == textViewTemperature) {
                return;
            }
            textViewTemperature.setText(String.format(Locale.US, "%.1f", temperature));
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
            textViewWindForce.setText("" + windForce);
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

        public void setWasAcknowledged(boolean ack) {
            if (null == ackIcon) {
                return;
            }
            ackIcon.setVisibility(ack ? View.VISIBLE : View.GONE);
        }

        public void setObjName(String objName) {
            if (null == textViewObjName || null == objName) {
                return;
            }
            textViewObjName.setText(objName);
        }
    }
}