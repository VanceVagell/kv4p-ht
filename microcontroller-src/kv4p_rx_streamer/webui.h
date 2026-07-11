/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <WebServer.h>
#include <Update.h>
#include <ArduinoJson.h>
#include <esp_task_wdt.h>
#include "config.h"
#include "radio.h"
#include "audio.h"
#include "streamer.h"
#include "frames.h"
#include "decoder.h"
#include "uplink.h"

WebServer server(80);

static const char INDEX_HTML[] PROGMEM = R"HTML(<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>kv4p RX streamer</title>
<style>
:root{color-scheme:dark}
body{font-family:system-ui,sans-serif;background:#14181c;color:#dde3e8;margin:0;padding:1rem;max-width:60rem;margin-inline:auto}
h1{font-size:1.3rem}h2{font-size:1.05rem;margin:1.5rem 0 .5rem;color:#8fb6d9}
section{background:#1c2228;border:1px solid #2a333c;border-radius:8px;padding:1rem;margin-bottom:1rem}
table{border-collapse:collapse;width:100%}
th,td{padding:.3rem .4rem;text-align:left;border-bottom:1px solid #2a333c;font-size:.9rem}
input,select,button{background:#242c34;color:#dde3e8;border:1px solid #3a4650;border-radius:4px;padding:.35rem .5rem;font-size:.9rem}
input[type=number]{width:7rem}input.num{width:3.5rem}input.name{width:8rem}
button{cursor:pointer}button.primary{background:#2b5c8a;border-color:#3a76ac}
button.danger{background:#6b2b2b;border-color:#8a3a3a}
.row{display:flex;gap:1rem;flex-wrap:wrap;align-items:center;margin:.4rem 0}
.stat{display:inline-block;margin-right:1.2rem;font-size:.9rem}.stat b{color:#8fb6d9}
#msg{position:fixed;top:.5rem;right:.5rem;background:#2b5c8a;padding:.5rem .8rem;border-radius:6px;display:none}
progress{width:100%;height:1rem}
code{background:#242c34;padding:.15rem .4rem;border-radius:4px}
</style></head><body>
<h1>kv4p RX streamer</h1>
<div id="msg"></div>

<section><h2>Status</h2><div id="status">loading…</div>
<div class="row">Stream URL: <code id="streamUrl"></code> <span style="opacity:.7">(open in VLC: Media &rarr; Open Network Stream)</span></div>
</section>

<section><h2>Tuning</h2>
<div class="row">
  Active: <select id="active"></select>
  <span id="vfoBox">VFO <input id="vfoFreq" type="number" step="0.0001" min="100" max="500"> MHz
  <select id="vfoBw"><option value="0">12.5 kHz</option><option value="1">25 kHz</option></select>
  <select id="vfoProto"><option value="0">no decode</option><option value="1">VDV R09</option><option value="2">NEMO LIO</option></select></span>
  <button class="primary" onclick="saveTuning()">Tune</button>
</div>
<div class="row">
  Volume <input id="volume" type="range" min="1" max="8"><span id="volumeV"></span>
  Squelch <input id="squelch" type="range" min="0" max="8"><span id="squelchV"></span>
  <label><input id="muteSq" type="checkbox"> mute while squelch closed</label>
  <button class="primary" onclick="saveAudio()">Apply</button>
</div>
</section>

<section><h2>Channels</h2>
<table id="chTable"><thead><tr><th>#</th><th>Name</th><th>Frequency (MHz)</th><th>Bandwidth</th><th>Mode</th><th>Protocol</th><th></th></tr></thead><tbody></tbody></table>
<div class="row"><button onclick="addRow()">Add channel</button><button class="primary" onclick="saveChannels()">Save channel table</button></div>
<div class="row" style="opacity:.7">On data channels set squelch to 0 — the decoders gate on their own signal envelope; hardware squelch clips telegram bursts.</div>
</section>

<section><h2>Backend uplink</h2>
<div class="row">Node name <input id="nodeName" maxlength="32"> Lat <input id="nodeLat" type="number" step="0.000001"> Lon <input id="nodeLon" type="number" step="0.000001"></div>
<div class="row">Endpoint <input id="upUrl" size="34" placeholder="https://… or wss://… (empty = off)"> Station key <input id="upTok" type="password" placeholder="(unchanged)"> <button class="primary" onclick="saveUplink()">Save uplink</button></div>
<div class="row" style="opacity:.7">Decoded telegram frames are pushed to this backend. Name and position are shown here for reference; the backend's station roster (keyed by the station key) places the node on the map.</div>
</section>

<section><h2>Network</h2>
<div class="row">WiFi SSID <input id="ssid"> Password <input id="pass" type="password" placeholder="(unchanged)"></div>
<div class="row">Stream port <input id="port" type="number" min="1" max="65535"> Admin password <input id="adminPass" type="password" placeholder="(unchanged, empty = none)"></div>
<div class="row"><button class="danger" onclick="saveNetwork()">Save &amp; reboot</button></div>
</section>

<section><h2>Firmware update</h2>
<div class="row"><input type="file" id="fw" accept=".bin"><button class="danger" onclick="uploadFw()">Upload &amp; flash</button></div>
<progress id="fwProg" value="0" max="100" style="display:none"></progress>
<div id="fwMsg"></div>
</section>

<script>
const $=id=>document.getElementById(id);
const BW=["12.5 kHz","25 kHz"], MODE=["voice","data"], PROTO=["none","VDV R09","NEMO LIO"];
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');
let chans=[];
function msg(t){const m=$('msg');m.textContent=t;m.style.display='block';setTimeout(()=>m.style.display='none',3000)}
async function api(path,opts){const r=await fetch(path,opts);if(!r.ok)throw new Error(await r.text());return r.json()}

async function refreshStatus(){try{
  const s=await api('/api/status');
  $('status').innerHTML=
    `<span class="stat">radio <b>${s.radio.moduleFound?'ok':'NOT FOUND'}</b></span>`+
    `<span class="stat">tuned <b>${(s.radio.freqHz/1e6).toFixed(4)} MHz</b> (${BW[s.radio.bandwidth]}${s.radio.channelName?', '+esc(s.radio.channelName):''})</span>`+
    `<span class="stat">squelch <b>${s.radio.squelchOpen?'open':'closed'}</b></span>`+
    `<span class="stat">wifi <b>${s.wifi.ssid||'setup AP'}</b> ${s.wifi.rssi?s.wifi.rssi+' dBm':''}</span>`+
    `<span class="stat">clients <b>${s.stream.clients}</b></span>`+
    `<span class="stat">overruns <b>${s.stream.overruns}</b></span>`+
    (s.decoder.proto?`<span class="stat">decoder <b>${PROTO[s.decoder.proto]}</b> bursts <b>${s.decoder.bursts}</b> frames <b>${s.decoder.frames}</b>${s.decoder.lastLabel?', last <b>'+esc(s.decoder.lastLabel)+'</b> '+s.decoder.lastAgeS+'s ago':''}</span>`:'')+
    (s.uplink.mode!=='off'?`<span class="stat">uplink <b>${s.uplink.mode}${s.uplink.connected?'':' ⚠'}</b> sent <b>${s.uplink.sent}</b> acc <b>${s.uplink.accepted}</b> drop <b>${s.uplink.dropped}</b>${s.uplink.lastError?' err <b>'+esc(s.uplink.lastError)+'</b>':''}</span>`:'')+
    `<span class="stat">heap <b>${Math.round(s.heap/1024)} kB</b></span>`+
    `<span class="stat">fw <b>v${s.version}</b></span>`;
  $('streamUrl').textContent=`http://${location.hostname}:${s.stream.port}/stream.wav`;
}catch(e){$('status').textContent='status unavailable: '+e.message}}

function rowHtml(c,i){return `<tr>
  <td><input class="num" type="number" min="0" max="255" value="${c.number}" data-i="${i}" data-k="number"></td>
  <td><input class="name" maxlength="16" value="${esc(c.name)}" data-i="${i}" data-k="name"></td>
  <td><input type="number" step="0.0001" value="${(c.freqHz/1e6).toFixed(4)}" data-i="${i}" data-k="freq"></td>
  <td><select data-i="${i}" data-k="bandwidth"><option value="0"${c.bandwidth==0?' selected':''}>12.5 kHz</option><option value="1"${c.bandwidth==1?' selected':''}>20/25 kHz</option></select></td>
  <td><select data-i="${i}" data-k="chMode" onchange="this.closest('tr').querySelector('[data-k=dataProto]').disabled=this.value=='0'"><option value="0"${c.chMode==0?' selected':''}>voice</option><option value="1"${c.chMode==1?' selected':''}>data</option></select></td>
  <td><select data-i="${i}" data-k="dataProto"${c.chMode==1?'':' disabled'}><option value="0"${(c.dataProto|0)==0?' selected':''}>none</option><option value="1"${c.dataProto==1?' selected':''}>VDV R09</option><option value="2"${c.dataProto==2?' selected':''}>NEMO LIO</option></select></td>
  <td><button onclick="delRow(${i})">✕</button></td></tr>`}

function renderChannels(){
  $('chTable').querySelector('tbody').innerHTML=chans.map(rowHtml).join('');
  const sel=$('active');const cur=sel.value;
  sel.innerHTML='<option value="-1">manual VFO</option>'+chans.map((c,i)=>`<option value="${i}">${c.number}: ${esc(c.name)||('ch'+c.number)}</option>`).join('');
  if(cur)sel.value=cur;
}
function readTable(){
  document.querySelectorAll('#chTable [data-i]').forEach(el=>{
    const c=chans[+el.dataset.i],k=el.dataset.k;
    if(k==='name')c.name=el.value;
    else if(k==='freq')c.freqHz=Math.round(parseFloat(el.value||'0')*1e6);
    else c[k]=+el.value;
  });
}
function addRow(){readTable();if(chans.length>=32){msg('max 32 channels');return}
  chans.push({number:chans.length+1,name:'',freqHz:146520000,bandwidth:0,chMode:0,dataProto:0});renderChannels()}
function delRow(i){readTable();chans.splice(i,1);renderChannels()}
async function saveChannels(){readTable();try{
  await api('/api/channels',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({channels:chans})});
  msg('channels saved');await loadAll();
}catch(e){msg('save failed: '+e.message)}}

async function saveTuning(){try{
  await api('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({
    activeChannel:+$('active').value,vfoFreqHz:Math.round(parseFloat($('vfoFreq').value)*1e6),vfoBandwidth:+$('vfoBw').value,vfoDataProto:+$('vfoProto').value})});
  msg('tuned');refreshStatus();
}catch(e){msg('tune failed: '+e.message)}}
async function saveUplink(){try{
  const body={nodeName:$('nodeName').value,nodeLat:parseFloat($('nodeLat').value||'0'),nodeLon:parseFloat($('nodeLon').value||'0'),uplinkUrl:$('upUrl').value.trim()};
  if($('upTok').value)body.uplinkToken=$('upTok').value;
  await api('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  $('upTok').value='';msg('uplink saved');refreshStatus();
}catch(e){msg('failed: '+e.message)}}
async function saveAudio(){try{
  await api('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({
    volume:+$('volume').value,squelch:+$('squelch').value,muteWhenClosed:$('muteSq').checked})});
  msg('applied');refreshStatus();
}catch(e){msg('failed: '+e.message)}}
async function saveNetwork(){if(!confirm('Save network settings and reboot?'))return;try{
  const body={ssid:$('ssid').value,streamPort:+$('port').value};
  if($('pass').value)body.pass=$('pass').value;
  if($('adminPass').value)body.adminPass=$('adminPass').value;
  await api('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  msg('rebooting…');
}catch(e){msg('failed: '+e.message)}}

function uploadFw(){
  const f=$('fw').files[0];if(!f){msg('choose a .bin first');return}
  if(!confirm(`Flash ${f.name} (${Math.round(f.size/1024)} kB)?`))return;
  const x=new XMLHttpRequest();x.open('POST','/update');
  $('fwProg').style.display='block';
  x.upload.onprogress=e=>{$('fwProg').value=100*e.loaded/e.total};
  x.onload=()=>{ $('fwMsg').textContent=x.status==200?'Update OK — rebooting. Refresh in ~15 s.':'Update failed: '+x.responseText};
  x.onerror=()=>{$('fwMsg').textContent='Upload error'};
  const fd=new FormData();fd.append('firmware',f);x.send(fd);
}

async function loadAll(){
  const c=await api('/api/config');
  chans=(await api('/api/channels')).channels;
  renderChannels();
  $('active').value=c.activeChannel;$('vfoFreq').value=(c.vfoFreqHz/1e6).toFixed(4);$('vfoBw').value=c.vfoBandwidth;
  $('vfoProto').value=c.vfoDataProto;
  $('volume').value=c.volume;$('squelch').value=c.squelch;$('muteSq').checked=c.muteWhenClosed;
  $('volumeV').textContent=c.volume;$('squelchV').textContent=c.squelch;
  $('ssid').value=c.ssid;$('port').value=c.streamPort;
  $('nodeName').value=c.nodeName;$('nodeLat').value=c.nodeLat;$('nodeLon').value=c.nodeLon;$('upUrl').value=c.uplinkUrl;
  $('volume').oninput=e=>$('volumeV').textContent=e.target.value;
  $('squelch').oninput=e=>$('squelchV').textContent=e.target.value;
  refreshStatus();
}
loadAll();setInterval(refreshStatus,2000);
</script></body></html>)HTML";

// --- helpers ---

static bool requireAuth() {
  if (cfg.adminPass.length() == 0) {
    return true;
  }
  if (!server.authenticate("admin", cfg.adminPass.c_str())) {
    server.requestAuthentication();
    return false;
  }
  return true;
}

static void sendJson(const JsonDocument &doc, int code = 200) {
  String out;
  serializeJson(doc, out);
  server.send(code, "application/json", out);
}

static void sendError(int code, const char *text) {
  server.send(code, "text/plain", text);
}

// --- handlers ---

static void handleStatus() {
  StaticJsonDocument<1664> doc;
  doc["version"] = FIRMWARE_VERSION;
  doc["uptime"] = millis() / 1000;
  doc["heap"] = ESP.getFreeHeap();
  JsonObject wifi = doc.createNestedObject("wifi");
  wifi["ssid"] = WiFi.status() == WL_CONNECTED ? cfg.ssid : "";
  wifi["rssi"] = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
  wifi["ip"] = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
  JsonObject radio = doc.createNestedObject("radio");
  uint32_t freqHz;
  uint8_t bandwidth;
  activeTuning(freqHz, bandwidth);
  radio["moduleFound"] = radioModuleFound;
  radio["freqHz"] = freqHz;
  radio["bandwidth"] = bandwidth;
  radio["activeChannel"] = cfg.activeChannel;
  if (cfg.activeChannel >= 0 && cfg.activeChannel < MAX_CHANNELS && channels.ch[cfg.activeChannel].used) {
    radio["channelName"] = channels.ch[cfg.activeChannel].name;
    radio["channelMode"] = channels.ch[cfg.activeChannel].chMode;
  }
  radio["squelchOpen"] = squelchOpen;
  JsonObject stream = doc.createNestedObject("stream");
  stream["port"] = cfg.streamPort;
  stream["clients"] = streamClientCount;
  stream["overruns"] = streamOverruns;
  stream["bytesOut"] = streamBytesOut;
  JsonObject decoder = doc.createNestedObject("decoder");
  decoder["proto"] = decRequestedProto;
  decoder["bursts"] = stBursts;
  decoder["frames"] = stFrames;
  decoder["feedDrops"] = decFeedDrops;
  if (stLastLabel[0]) {
    decoder["lastLabel"] = stLastLabel;
    decoder["lastAgeS"] = (millis() - stLastMs) / 1000;
  }
  JsonObject uplink = doc.createNestedObject("uplink");
  uplink["mode"] = uplinkModeName();
  uplink["connected"] = uplinkConnected();
  uplink["sent"] = stSent;
  uplink["accepted"] = stAccepted;
  uplink["dropped"] = stDropped;
  uplink["queued"] = frameQueue ? uxQueueMessagesWaiting(frameQueue) : 0;
  if (upLastError[0]) uplink["lastError"] = upLastError;
  sendJson(doc);
}

static void handleGetConfig() {
  StaticJsonDocument<896> doc;
  doc["ssid"] = cfg.ssid;
  doc["streamPort"] = cfg.streamPort;
  doc["volume"] = cfg.volume;
  doc["squelch"] = cfg.squelch;
  doc["activeChannel"] = cfg.activeChannel;
  doc["vfoFreqHz"] = cfg.vfoFreqHz;
  doc["vfoBandwidth"] = cfg.vfoBandwidth;
  doc["muteWhenClosed"] = cfg.muteWhenClosed;
  doc["vfoDataProto"] = cfg.vfoDataProto;
  doc["nodeName"] = cfg.nodeName;
  doc["nodeLat"] = cfg.nodeLat;
  doc["nodeLon"] = cfg.nodeLon;
  doc["uplinkUrl"] = cfg.uplinkUrl;
  doc["hasUplinkToken"] = cfg.uplinkToken.length() > 0;  // the key itself is never echoed
  doc["hasAdminPass"] = cfg.adminPass.length() > 0;
  sendJson(doc);
}

static void handlePostConfig() {
  if (!requireAuth()) return;
  StaticJsonDocument<1024> doc;
  if (deserializeJson(doc, server.arg("plain"))) {
    return sendError(400, "bad json");
  }
  bool needReboot = false;
  bool uplinkChanged = false;
  if (doc.containsKey("ssid") && doc["ssid"].as<String>() != cfg.ssid) { cfg.ssid = doc["ssid"].as<String>(); needReboot = true; }
  if (doc.containsKey("pass")) { cfg.pass = doc["pass"].as<String>(); needReboot = true; }
  if (doc.containsKey("adminPass")) cfg.adminPass = doc["adminPass"].as<String>();
  if (doc.containsKey("streamPort")) {
    uint16_t p = doc["streamPort"];
    if (p != cfg.streamPort) { cfg.streamPort = p; needReboot = true; }
  }
  if (doc.containsKey("volume")) cfg.volume = constrain(doc["volume"].as<int>(), 1, 8);
  if (doc.containsKey("squelch")) cfg.squelch = constrain(doc["squelch"].as<int>(), 0, 8);
  if (doc.containsKey("muteWhenClosed")) cfg.muteWhenClosed = doc["muteWhenClosed"];
  if (doc.containsKey("vfoFreqHz")) {
    uint32_t f = doc["vfoFreqHz"];
    if (!freqInModuleRange(f)) return sendError(400, "frequency outside module range");
    cfg.vfoFreqHz = f;
  }
  if (doc.containsKey("vfoBandwidth")) cfg.vfoBandwidth = doc["vfoBandwidth"].as<int>() ? 1 : 0;
  if (doc.containsKey("activeChannel")) {
    int8_t a = doc["activeChannel"];
    if (a >= MAX_CHANNELS || (a >= 0 && !channels.ch[a].used)) return sendError(400, "no such channel");
    cfg.activeChannel = a < 0 ? -1 : a;
  }
  if (doc.containsKey("vfoDataProto")) cfg.vfoDataProto = constrain(doc["vfoDataProto"].as<int>(), 0, 2);
  if (doc.containsKey("nodeName")) cfg.nodeName = doc["nodeName"].as<String>();
  if (doc.containsKey("nodeLat")) cfg.nodeLat = doc["nodeLat"].as<float>();
  if (doc.containsKey("nodeLon")) cfg.nodeLon = doc["nodeLon"].as<float>();
  if (doc.containsKey("uplinkUrl")) { cfg.uplinkUrl = doc["uplinkUrl"].as<String>(); uplinkChanged = true; }
  if (doc.containsKey("uplinkToken")) { cfg.uplinkToken = doc["uplinkToken"].as<String>(); uplinkChanged = true; }
  saveConfig();
  applyRadioTuning();
  decoderReconfigure();  // the active channel / VFO protocol may have changed
  if (uplinkChanged) uplinkReconfigure();
  StaticJsonDocument<64> resp;
  resp["ok"] = true;
  resp["reboot"] = needReboot;
  sendJson(resp);
  if (needReboot) {
    delay(500);
    ESP.restart();
  }
}

static void handleGetChannels() {
  DynamicJsonDocument doc(8192);
  JsonArray arr = doc.createNestedArray("channels");
  for (int i = 0; i < MAX_CHANNELS; i++) {
    if (!channels.ch[i].used) continue;
    JsonObject o = arr.createNestedObject();
    o["number"] = channels.ch[i].number;
    o["name"] = channels.ch[i].name;
    o["freqHz"] = channels.ch[i].freqHz;
    o["bandwidth"] = channels.ch[i].bandwidth;
    o["chMode"] = channels.ch[i].chMode;
    o["dataProto"] = channels.ch[i].dataProto;
  }
  sendJson(doc);
}

static void handlePutChannels() {
  if (!requireAuth()) return;
  DynamicJsonDocument doc(8192);
  if (deserializeJson(doc, server.arg("plain"))) {
    return sendError(400, "bad json");
  }
  JsonArray arr = doc["channels"];
  if (arr.isNull() || arr.size() > MAX_CHANNELS) {
    return sendError(400, "channels must be an array of at most 32");
  }
  for (JsonObject o : arr) {
    if (!freqInModuleRange(o["freqHz"] | 0u)) {
      return sendError(400, "frequency outside module range");
    }
  }
  resetChannelTable();
  int i = 0;
  for (JsonObject o : arr) {
    Channel &c = channels.ch[i++];
    c.number = o["number"] | 0;
    strlcpy(c.name, o["name"] | "", sizeof(c.name));
    c.freqHz = o["freqHz"];
    c.bandwidth = (o["bandwidth"] | 0) ? 1 : 0;
    c.chMode = (o["chMode"] | 0) ? CH_MODE_DATA : CH_MODE_VOICE;
    c.dataProto = constrain(o["dataProto"] | 0, 0, 2);
    c.used = 1;
  }
  if (cfg.activeChannel >= i) {
    cfg.activeChannel = -1;
    saveConfig();
  }
  saveChannelTable();
  applyRadioTuning();    // the active channel's definition may have changed
  decoderReconfigure();  // ... including its data protocol
  StaticJsonDocument<32> resp;
  resp["ok"] = true;
  sendJson(resp);
}

// Browser-based OTA: streams the uploaded .bin into the inactive OTA slot.
// A bad/truncated image fails Update.end() and the device stays on the old
// firmware — no brick. Devices may be mounted out of reach, so this is the
// primary way users get new versions.
static void handleUpdateUpload() {
  HTTPUpload &up = server.upload();
  esp_task_wdt_reset();  // upload chunks arrive on the watched loop task
  if (up.status == UPLOAD_FILE_START) {
    otaInProgress = true;  // streamer task drops clients and idles
    Serial.printf("[ota] receiving %s\n", up.filename.c_str());
    if (!Update.begin(UPDATE_SIZE_UNKNOWN)) {
      Serial.printf("[ota] begin failed: %s\n", Update.errorString());
    }
  } else if (up.status == UPLOAD_FILE_WRITE) {
    if (Update.isRunning() && Update.write(up.buf, up.currentSize) != up.currentSize) {
      Serial.printf("[ota] write failed: %s\n", Update.errorString());
      Update.abort();
    }
  } else if (up.status == UPLOAD_FILE_END) {
    if (Update.isRunning() && Update.end(true)) {
      Serial.printf("[ota] success, %u bytes\n", up.totalSize);
    } else {
      Serial.printf("[ota] end failed: %s\n", Update.errorString());
    }
  } else if (up.status == UPLOAD_FILE_ABORTED) {
    Update.abort();
    otaInProgress = false;  // client vanished; the completion handler may never run
  }
}

static void handleUpdateDone() {
  otaInProgress = false;
  if (Update.hasError()) {
    sendError(500, Update.errorString());
  } else {
    server.send(200, "text/plain", "OK");
    delay(500);
    ESP.restart();
  }
}

void webSetup() {
  server.on("/", HTTP_GET, []() {
    server.send_P(200, "text/html", INDEX_HTML);
  });
  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/config", HTTP_GET, handleGetConfig);
  server.on("/api/config", HTTP_POST, handlePostConfig);
  server.on("/api/channels", HTTP_GET, handleGetChannels);
  server.on("/api/channels", HTTP_PUT, handlePutChannels);
  server.on("/update", HTTP_POST, []() {
    if (!requireAuth()) { otaInProgress = false; return; }
    handleUpdateDone();
  }, []() {
    if (cfg.adminPass.length() && !server.authenticate("admin", cfg.adminPass.c_str())) {
      return;  // reject upload chunks silently; the completion handler 401s
    }
    handleUpdateUpload();
  });
  server.onNotFound([]() {
    sendError(404, "not found");
  });
  server.begin();
}
