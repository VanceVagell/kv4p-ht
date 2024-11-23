#include <Arduino.h>
#include <kv4p-ht-v2.h>
#include <Adafruit_NeoPixel.h>

void print_buttons() {
    static bool PTT_Left, PTT_Right, Program;
    bool tmp;

    tmp = digitalRead(PIN_BUTTON_PTT_LEFT);
    if (PTT_Left != tmp) {
        Serial.print("PTT Left: ");
        Serial.println(tmp ? "Released" : "Pressed");
        PTT_Left = tmp;
    }
    tmp = digitalRead(PIN_BUTTON_PTT_RIGHT);
    if (PTT_Right != tmp) {
        Serial.print("PTT Right: ");
        Serial.println(tmp ? "Released" : "Pressed");
        PTT_Right = tmp;
    }
    tmp = digitalRead(PIN_BUTTON_PROGRAM);
    if (Program != tmp) {
        Serial.print("Program: ");
        Serial.println(tmp ? "Released" : "Pressed");
        Program = tmp;
    }
}

Adafruit_NeoPixel pixels(1, PIN_NEOPIXEL, NEO_GRBW + NEO_KHZ800);

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

void setup() {
    Serial.begin(921600);
    pinMode(PIN_STOCK_LED, OUTPUT);
    pinMode(PIN_BUTTON_PTT_LEFT, INPUT);
    pinMode(PIN_BUTTON_PTT_RIGHT, INPUT);
    pinMode(PIN_BUTTON_PROGRAM, INPUT);
    pixels.begin();
    pixels.setBrightness(32);
    delay(500);
}

void loop() {
    static uint8_t phase = 0;
    uint32_t now=millis();
    digitalWrite(PIN_STOCK_LED, (now/500)%2 ? HIGH : LOW);
    print_buttons();
    
    if (phase != (now/900)%5) {
        phase = (now/900)%5;
        Serial.printf("Phase: %d\n", phase);
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
    delay(10);
}

