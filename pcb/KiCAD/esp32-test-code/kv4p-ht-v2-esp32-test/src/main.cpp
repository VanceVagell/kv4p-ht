#include <Arduino.h>
#include <kv4p-ht-v2.h>
#include <Adafruit_NeoPixel.h>
#include "DRA818.h"

#ifndef USE_SERIAL
#define USE_SERIAL true
#endif
const bool IF_SERIAL=USE_SERIAL;

void print_buttons() {
    static bool PTT_Left, PTT_Right, Program;
    bool tmp;

    tmp = digitalRead(PIN_BUTTON_PTT_LEFT);
    if (PTT_Left != tmp) {
        IF_SERIAL && Serial.print("PTT Left: ");
        IF_SERIAL && Serial.println(tmp ? "Released" : "Pressed");
        PTT_Left = tmp;
    }
    tmp = digitalRead(PIN_BUTTON_PTT_RIGHT);
    if (PTT_Right != tmp) {
        IF_SERIAL && Serial.print("PTT Right: ");
        IF_SERIAL && Serial.println(tmp ? "Released" : "Pressed");
        PTT_Right = tmp;
    }
    tmp = digitalRead(PIN_BUTTON_PROGRAM);
    if (Program != tmp) {
        IF_SERIAL && Serial.print("Program: ");
        IF_SERIAL && Serial.println(tmp ? "Released" : "Pressed");
        Program = tmp;
    }
}

Adafruit_NeoPixel pixels(1, PIN_NEOPIXEL, NEO_GRBW + NEO_KHZ400);
DRA818 *dra;

uint8_t phase(uint32_t now, uint32_t cycle_length, uint32_t offset) {
    uint32_t phase = (now/cycle_length + offset) % cycle_length;
    float cycle3 = cycle_length/3;
    if (phase <= cycle3) { // Ramp Up
        return uint8_t((float(phase)/cycle3) * 255);
    } else if (phase <= cycle3 * 2) { // Ramp Down
        return uint8_t((float(cycle3*2 - phase)/cycle3) * 255);
    }
    return 0;
}

float line(uint16_t reading, float start_v, uint16_t start_r, float end_v, uint16_t end_r) {
    float v = (end_v - start_v) * float(reading - start_r)/(end_r - start_r) + start_v;
    return float(int(v*100.0))/100.0;
}

float adc_map(uint16_t reading) {
    // This is a rough translation of the graph on 
    // https://randomnerdtutorials.com/esp32-adc-analog-read-arduino-ide/

    if (reading <= 3200) {
        // 0 = 0.15, 2.7v = 3200
        return line(reading, 0.15, 0, 2.70, 3200);
    } else {
        // 3.15v = 4095
        return line(reading, 2.7, 3200, 3.15, 4095);
    }
}

uint32_t last_time;
bool PTT_STATE;
bool COR_STATE;

// Set this as appropriate.  ESP32 is 12 bit.
const uint8_t ADC_BITS = 12;
const uint16_t ADC_MAX = (1<<ADC_BITS) - 1;

void setup() {
    Serial.begin(230400);
    delay(500);
    IF_SERIAL && Serial.println("Booting!");

    pinMode(PIN_STOCK_LED, OUTPUT);
    pinMode(PIN_BUTTON_PTT_LEFT, INPUT);
    pinMode(PIN_BUTTON_PTT_RIGHT, INPUT);
    pinMode(PIN_BUTTON_PROGRAM, INPUT);
    pixels.begin();
    pixels.setBrightness(32);
    delay(500);

    pinMode(PIN_RADIO_PD, OUTPUT);
    digitalWrite(PIN_RADIO_PD, HIGH);
    pinMode(PIN_RADIO_PTT, OUTPUT);
    digitalWrite(PIN_RADIO_PTT, HIGH); // Active low
    pinMode(PIN_BUTTON_PTT_LEFT, INPUT_PULLUP);
    pinMode(PIN_BUTTON_PTT_RIGHT, INPUT_PULLUP);
    pinMode(PIN_RADIO_SQUELCH, INPUT);

    pinMode(PIN_GPIOHEAD_6, ANALOG);
    analogSetWidth(12);
    // Communication with DRA818V radio module via GPIO pins
    IF_SERIAL && Serial.println("Setting up serial port to 818 module.");
    Serial2.begin(9600, SERIAL_8N1, PIN_RX_FROM_RADIO, PIN_TX_TO_RADIO); 
    dra = new DRA818(&Serial2, DRA818_VHF);

    IF_SERIAL && Serial.print("Attempting handshake: ");
    uint8_t ret = 0;
    ret = dra->handshake();
    IF_SERIAL && Serial.println(ret ? "Success!" : "Failure.");

    if (ret) {
        IF_SERIAL && Serial.print("Attempting Group: ");
        ret = dra->group(DRA818_12K5, 146.580, 146.580, 0, 8, 0);
        IF_SERIAL && Serial.println(ret ? "Group success!" : "Group failure.");
    }
    Serial2.println("AT+VERSION");  // Will print result in loop()

    last_time = millis();
    PTT_STATE = false;
    COR_STATE = digitalRead(PIN_RADIO_SQUELCH);
}


void loop() {
    static uint8_t phase = 0;
    uint32_t now=millis();
    digitalWrite(PIN_STOCK_LED, (now/500)%2 ? HIGH : LOW);
    print_buttons();
    if (phase != (now/900)%5) {
        phase = (now/900)%5;
        //Serial.println("Phase: " + String(phase));
    }
    uint8_t red = (phase == 0) ? 255:0;
    uint8_t green = (phase == 1) ? 255:0;
    uint8_t blue = (phase == 2) ? 255:0;
    if (!digitalRead(PIN_BUTTON_PTT_LEFT)) {
        red = 255;
    }
    if (!digitalRead(PIN_BUTTON_PTT_RIGHT)) {
        green = 255;
    }
    if (!digitalRead(PIN_BUTTON_PROGRAM)) {
        blue = 255;
    }
    pixels.setPixelColor(0, pixels.Color(red, green, blue));
    pixels.show();


    while (Serial2.available()) {
        char c = Serial2.read();
        IF_SERIAL && Serial.print(c);
    }
    while (Serial.available()) {
        char c = Serial.read();
        IF_SERIAL && Serial2.print(c);
        IF_SERIAL && Serial.print(c);
    }

    bool NEW_PTT = (digitalRead(PIN_BUTTON_PTT_LEFT) == LOW || digitalRead(PIN_BUTTON_PTT_RIGHT) == LOW);

    if (PTT_STATE != NEW_PTT) {
        PTT_STATE = NEW_PTT;
        digitalWrite(PIN_RADIO_PTT, PTT_STATE ? LOW : HIGH);
        IF_SERIAL && Serial.println(PTT_STATE ? "PTT" : "RTL");
    }

    bool NEW_COR = (digitalRead(PIN_RADIO_SQUELCH) == LOW);
    if (COR_STATE != NEW_COR) {
        COR_STATE = NEW_COR;
        IF_SERIAL && Serial.println(COR_STATE ? "Squelch open" : "Squelch closed");
    }

    if (last_time/5000 != now/5000) {
        // Once a second
        //IF_SERIAL && Serial.println("Boop.");
        //IF_SERIAL && Serial.println(dra->handshake());
        IF_SERIAL && Serial2.print("RSSI?\r\n");

        float V = adc_map(analogRead(PIN_GPIOHEAD_6));
        // Voltage divider in hardware, hence *2
        IF_SERIAL && Serial.println(String(V*2) + "v");

    }

    last_time = now;
    delay(10);
}

