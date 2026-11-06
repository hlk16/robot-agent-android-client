#include "BluetoothSerial.h"
#include <ESP32Servo.h>
BluetoothSerial SerialBT;
Servo servo_33;
Servo servo_32;
Servo servo_27;
Servo servo_26;
volatile char c;
void reset() {
  servo_33.write(90);
  delay(0);
  servo_32.write(180);
  delay(0);
  servo_27.write(0);
  delay(0);
  servo_26.write(90);
  delay(0);
}
void clap() {
  servo_32.write(90);
  delay(0);
  servo_27.write(90);
  delay(1000);
  servo_33.write(110);
  delay(0);
  servo_26.write(70);
  delay(1000);
  servo_33.write(90);
  delay(0);
  servo_26.write(90);
  delay(1000);
  servo_33.write(110);
  delay(0);
  servo_26.write(70);
  delay(1000);
  servo_33.write(90);
  delay(0);
  servo_26.write(90);
  delay(1000);
  reset();
  Serial.println("鼓掌执行一次");
}
void wave() {
  servo_27.write(120);
  delay(1000);
  servo_26.write(80);
  delay(1000);
  servo_26.write(100);
  delay(1000);
  servo_26.write(80);
  delay(1000);
  servo_26.write(90);
  delay(1000);
  servo_27.write(0);
  delay(1000);
  Serial.println("挥手执行一次");
}
void salute() {
  servo_27.write(120);
  delay(1000);
  servo_26.write(70);
  delay(3000);
  servo_26.write(90);
  delay(1000);
  servo_27.write(0);
  delay(1000);
  Serial.println("敬礼执行一次");
}
void setup(){
  SerialBT.begin("AESP32");
  Serial.println("The device started, now you can pair it with bluetooth!");
  Serial.begin(115200);
  servo_33.attach(33,500,2500);
  servo_32.attach(32,500,2500);
  servo_27.attach(27,500,2500);
  servo_26.attach(26,500,2500);
  c = 0;
  servo_33.write(90);
  delay(0);
  servo_32.write(180);
  delay(0);
  servo_27.write(0);
  delay(0);
  servo_26.write(90);
  delay(0);
}
void loop(){
  if (SerialBT.available() > 0) {
    c = SerialBT.read();
    Serial.println(c);
    if ('f' == c) {
      wave();
    }
    if ('g' == c) {
      clap();
    }
  }
}