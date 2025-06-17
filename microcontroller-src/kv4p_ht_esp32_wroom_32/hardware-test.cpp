#include <Arduino.h>
#include "DRA818.h"
#include "globals.h"
#include "led.h"
#include "hardware-test.h"

// Previously defined in globals.h
#define PIN_RADIO_PTT PTT_PIN
#define PIN_RADIO_PD PD_PIN
#define PIN_RADIO_SQUELCH SQ_PIN_HW2
#define PIN_RX_FROM_RADIO RXD2_PIN
#define PIN_TX_TO_RADIO TXD2_PIN

void printButtons() {
    static bool pttLeft, pttRight, program;
    bool tmp;

    tmp = digitalRead(PIN_BUTTON_PTT_LEFT);
    if (pttLeft != tmp) {
        Serial.print("PTT Left: ");
        Serial.println(tmp ? "Released" : "Pressed");
        pttLeft = tmp;
    }
    tmp = digitalRead(PIN_BUTTON_PTT_RIGHT);
    if (pttRight != tmp) {
        Serial.print("PTT Right: ");
        Serial.println(tmp ? "Released" : "Pressed");
        pttRight = tmp;
    }
    tmp = digitalRead(PIN_BUTTON_PROGRAM);
    if (program != tmp) {
        Serial.print("Program: ");
        Serial.println(tmp ? "Released" : "Pressed");
        program = tmp;
    }
}

DRA818 *dra;

uint8_t phase(uint32_t now, uint32_t cycleLength, uint32_t offset) {
    uint32_t phase = (now/cycleLength + offset) % cycleLength;
    float cycle3 = cycleLength/3;
    if (phase <= cycle3) { // Ramp Up
        return uint8_t((float(phase)/cycle3) * 255);
    } else if (phase <= cycle3 * 2) { // Ramp Down
        return uint8_t((float(cycle3*2 - phase)/cycle3) * 255);
    }
    return 0;
}

float line(uint16_t reading, float startV, uint16_t startR, float endV, uint16_t endR) {
    float v = (endV - startV) * float(reading - startR)/(endR - startR) + startV;
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

uint32_t lastTime;
bool pttState;
bool corState;

// Set this as appropriate.  ESP32 is 12 bit.
const uint8_t ADC_BITS = 12;
const uint16_t ADC_MAX = (1<<ADC_BITS) - 1;

bool hasPttReleasedYet;

void testLoop() {
    static uint8_t phase = 0;
    uint32_t now=millis();
    //digitalWrite(PIN_STOCK_LED, (now/500)%2 ? HIGH : LOW);
    printButtons();
    if (phase != (now/900)%5) {
        phase = (now/900)%5;
        //Serial.println("Phase: " + String(phase));
    }
    RGBColor color;
    color.red = (phase == 0) ? 255:0;
    color.green = (phase == 1) ? 255:0;
    color.blue = (phase == 2) ? 255:0;

    if (!digitalRead(PIN_BUTTON_PTT_LEFT)) {
        color.red = 255;
    }
    if (!digitalRead(PIN_BUTTON_PTT_RIGHT)) {
        color.green = 255;
    }
    if (!digitalRead(PIN_BUTTON_PROGRAM)) {
        color.blue = 255;
    }
    neopixelColor(color, 32);

    if (Serial2.available()) {
        digitalWrite(PIN_STOCK_LED, HIGH);
        while (Serial2.available()) {
            char c = Serial2.read();
            Serial.print(c);
        }
    }
    else {
        digitalWrite(PIN_STOCK_LED, LOW);
    }
    while (Serial.available()) {
        char c = Serial.read();
        Serial2.print(c);
        Serial.print(c);
    }

    bool newPtt = (digitalRead(PIN_BUTTON_PTT_LEFT) == LOW || digitalRead(PIN_BUTTON_PTT_RIGHT) == LOW);

    if (!hasPttReleasedYet && !newPtt) {
        hasPttReleasedYet = true;
        Serial.println("PTT released.  PTT will now key radio module.");
    }

    if (hasPttReleasedYet && (pttState != newPtt)) {
        pttState = newPtt;
        digitalWrite(PIN_RADIO_PTT, pttState ? LOW : HIGH);
        Serial.println(pttState ? "PTT" : "RTL");
    }

    bool newCor = (digitalRead(PIN_RADIO_SQUELCH) == LOW);
    if (corState != newCor) {
        corState = newCor;
        Serial.println(corState ? "Squelch open" : "Squelch closed");
    }

    if (lastTime/3000 != now/3000) {
        // Once a second
        //Serial.println("Boop.");
        //Serial.println(dra->handshake());
        Serial2.print("RSSI?\r\n");

        float V = adc_map(analogRead(PIN_GPIOHEAD_6));
        // Voltage divider in hardware, hence *2
        Serial.println(String(V*2) + "v");

    }

    lastTime = now;
    delay(10);
}

void testSetup(void) {
    //Serial.begin(230400);   // Already setup in the proper setup()
    delay(500);
    Serial.println("======== Entering Hardware Test Mode ========");
    Serial.println("======== If the SA818 is installed, CONNECT A 50OHM LOAD!! ========");
    Serial.println("== Test Actions:");
    Serial.println("* Pressing the PTT buttons will trigger PTT on the SA818, if present.  MAKE SURE TO CONNECT A 50OHM LOAD!");
    Serial.println("* The NeoPixel will cycle 1 second each: Red, Green, Blue, Black, Black. (ie: Black for 2 seconds.)");
    Serial.println("* Pressing buttons will also light up each color on the NeoPixel:");
    Serial.println("   * Left PTT: Red");
    Serial.println("   * Right PTT: Green");
    Serial.println("   * Boot/Program: Blue");
    Serial.println("* The 'stock LED' will light up any time the SA818 sends serial data back to the microcontroller.");
    Serial.println("* The microcontroller attempts a handshake with the SA818 at start up, and periodically requests an RSSI report.");
    Serial.println("  The RSSI report causes the SA818 to send some serial data to the micro, causing the stock LED to blink.");
    Serial.println("* The microcontroller periodically measures the +5v power line and prints the voltage. (Not very precise.)");
    Serial.println("");
    Serial.println("==  How to read results:");
    Serial.println("* Pressing all three buttons (with no radio, and/or WITH A 50OHM LOAD!) tests the buttons and LEDs.");
    Serial.println("* Short blinks on the stock LED confirm serial communication with the radio module, both transmit and receive.");
    Serial.println("* A dummy load and watt meter on the RF output can test the SA818's RF path, and the ability to key PTT.");
    Serial.println("== TODO: Method for testing the audio path.");
    Serial.println("");
    Serial.println("");

    // LEDs and buttons.
    pinMode(PIN_STOCK_LED, OUTPUT);
    pinMode(PIN_BUTTON_PTT_LEFT, INPUT_PULLUP);
    pinMode(PIN_BUTTON_PTT_RIGHT, INPUT_PULLUP);
    pinMode(PIN_BUTTON_PROGRAM, INPUT);
    ledSetup();
    delay(500);

    // Put the radio into a sane state.
    pinMode(PIN_RADIO_PD, OUTPUT);
    digitalWrite(PIN_RADIO_PD, HIGH);
    pinMode(PIN_RADIO_PTT, OUTPUT);
    digitalWrite(PIN_RADIO_PTT, HIGH); // Active low
    pinMode(PIN_RADIO_SQUELCH, INPUT);
    pinMode(PIN_RADIO_HI_LOW, OUTPUT);
    digitalWrite(PIN_RADIO_HI_LOW, LOW);  // high here enabled the MOSFET pulldown for low power.  Keep in low power testing.

    // Reading Vcc
    pinMode(PIN_GPIOHEAD_6, ANALOG);
    analogSetWidth(12);

    // Communication with DRA818V radio module via GPIO pins
    Serial.println("Setting up serial port to 818 module.");
    Serial2.begin(9600, SERIAL_8N1, PIN_RX_FROM_RADIO, PIN_TX_TO_RADIO); 
    dra = new DRA818(&Serial2, DRA818_VHF);

    Serial.print("Attempting handshake: ");
    uint8_t ret = 0;
    ret = dra->handshake();  // Damn, can't lower the timeout.
    Serial.println(ret ? "Success!" : "Failure.");

    if (ret) {
        Serial.print("Attempting Group: ");
        ret = dra->group(DRA818_12K5, 146.580, 146.580, 0, 8, 0);
        Serial.println(ret ? "Group success!" : "Group failure.");
    }
    Serial2.println("AT+VERSION");  // Will print result in loop()

    lastTime = millis();
    pttState = false;
    corState = digitalRead(PIN_RADIO_SQUELCH);

    // We press PTT to get into hardware test mode. Don't actually key anything up until
    // both PTT buttons have been released.
    hasPttReleasedYet = false;

    // Run our own loop()
    while (true) testLoop();
}


