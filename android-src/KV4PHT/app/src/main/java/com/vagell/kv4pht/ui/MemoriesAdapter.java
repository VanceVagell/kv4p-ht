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

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.ChannelMemory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MemoriesAdapter extends RecyclerView.Adapter<MemoriesAdapter.MemoryViewHolder> {

    public List<ChannelMemory> memoriesList;
    private final List<ChannelMemory> allMemoriesList;
    private MemoryListener memoryListener;
    private String groupFilter = null;
    private boolean bandFilterEnabled = false;
    private float minRadioFreq = 0.0f;
    private float maxRadioFreq = 999.0f;

    public interface MemoryListener {
        void onMemoryClick(ChannelMemory memory);
        void onMemoryDelete(ChannelMemory memory);
        void onMemoryEdit(ChannelMemory memory);
    }

    public MemoriesAdapter(MemoryListener listener) {
        this.memoriesList = new ArrayList<>();
        this.allMemoriesList = new ArrayList<>();
        this.memoryListener = listener;
    }

    @NonNull
    @Override
    public MemoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.memory_row, parent, false);
        return new MemoryViewHolder(itemView);
    }

    public void setMemoriesList(List<ChannelMemory> memoriesList) {
        this.allMemoriesList.clear();
        if (memoriesList != null) {
            this.allMemoriesList.addAll(memoriesList);
        }
        applyFilters();
    }

    public void setGroupFilter(String groupFilter) {
        this.groupFilter = groupFilter;
        applyFilters();
    }

    public void clearBandFilter() {
        this.bandFilterEnabled = false;
        applyFilters();
    }

    public void setBandFilter(float minRadioFreq, float maxRadioFreq) {
        this.bandFilterEnabled = true;
        this.minRadioFreq = minRadioFreq;
        this.maxRadioFreq = maxRadioFreq;
        applyFilters();
    }

    private void applyFilters() {
        memoriesList = new ArrayList<>();
        for (ChannelMemory memory : allMemoriesList) {
            if (groupFilter != null && !groupFilter.equals(memory.group)) {
                continue;
            }
            if (bandFilterEnabled && !memoryFitsBand(memory)) {
                continue;
            }
            memoriesList.add(memory);
        }
    }

    private boolean memoryFitsBand(ChannelMemory memory) {
        try {
            float rxFreq = Float.parseFloat(memory.frequency);
            float txFreq = txFrequency(memory);
            return frequencyFitsBand(rxFreq) && frequencyFitsBand(txFreq);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private boolean frequencyFitsBand(float frequency) {
        return frequency >= minRadioFreq && frequency <= maxRadioFreq;
    }

    private float txFrequency(ChannelMemory memory) {
        float txFreq = Float.parseFloat(memory.frequency);
        if (memory.offset == ChannelMemory.OFFSET_UP) {
            txFreq += memory.offsetKhz / 1000f;
        } else if (memory.offset == ChannelMemory.OFFSET_DOWN) {
            txFreq -= memory.offsetKhz / 1000f;
        }
        return txFreq;
    }

    public List<String> visibleGroups() {
        Set<String> groups = new LinkedHashSet<>();
        for (ChannelMemory memory : allMemoriesList) {
            if (bandFilterEnabled && !memoryFitsBand(memory)) {
                continue;
            }
            if (memory.group != null && memory.group.trim().length() > 0) {
                groups.add(memory.group);
            }
        }
        return new ArrayList<>(groups);
    }

    @Override
    public void onBindViewHolder(@NonNull MemoryViewHolder holder, int position) {
        ChannelMemory memory = memoriesList.get(position);

        holder.setName(memory.name);
        holder.setFrequency(memory.frequency);
        holder.setHighlighted(memory.isHighlighted());

        // Handle taps on the memory itself
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                memoryListener.onMemoryClick(memory);
            }
        });

        // Handle taps on the memory's menu icon
        final View memoryMenuButton = holder.itemView.findViewById(R.id.memoryMenu);
        memoryMenuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Context themedContext = new ContextThemeWrapper(holder.itemView.getContext(), R.style.Custom_PopupMenu);
                PopupMenu memoryMenu = new PopupMenu(themedContext, memoryMenuButton);
                memoryMenu.inflate(R.menu.memory_row_menu);
                memoryMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        String deleteTitle = themedContext.getString(R.string.delete);
                        String editTitle = themedContext.getString(R.string.edit);

                        if (item.getTitle().equals(deleteTitle)) {
                            memoryListener.onMemoryDelete(memory);
                        } else if (item.getTitle().equals(editTitle)) {
                            memoryListener.onMemoryEdit(memory);
                        }
                        return true;
                    }
                });
                memoryMenu.show();
            }
        });
    }

    @Override
    public int getItemCount() {
        return memoriesList.size();
    }

    static class MemoryViewHolder extends RecyclerView.ViewHolder {
        TextView textViewName;
        TextView textViewFrequency;

        public MemoryViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewName = itemView.findViewById(R.id.memoryName);
            textViewFrequency = itemView.findViewById(R.id.memoryFrequency);
        }

        public void setName(String name) {
            textViewName.setText(name);
        }

        public void setFrequency(String frequency) {
            textViewFrequency.setText(frequency);
        }

        public void setHighlighted(boolean highlighted) {
            if (highlighted) {
                itemView.findViewById(R.id.memoryContainer)
                        .setBackgroundColor(itemView.getResources().getColor(R.color.primary_veryfaint));
                itemView.findViewById(R.id.memoryMenu).setVisibility(View.VISIBLE);
                ((TextView) itemView.findViewById(R.id.memoryName)).setTextColor(itemView.getResources().getColor(R.color.primary));
                ((TextView) itemView.findViewById(R.id.memoryFrequency)).setTextColor(itemView.getResources().getColor(R.color.primary));
            } else {
                itemView.setBackgroundColor(itemView.getResources().getColor(R.color.clear));
                itemView.findViewById(R.id.memoryMenu).setVisibility(View.GONE);
                ((TextView) itemView.findViewById(R.id.memoryName)).setTextColor(itemView.getResources().getColor(R.color.primary_deselected));
                ((TextView) itemView.findViewById(R.id.memoryFrequency)).setTextColor(itemView.getResources().getColor(R.color.primary_deselected));
            }
        }
    }
}