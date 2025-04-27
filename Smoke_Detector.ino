#include <SoftwareSerial.h>
#include <DHT.h>
#include <TinyGPS++.h>

// Bluetooth Module HC-05
SoftwareSerial BS(9, 2); // RX, TX
int command;
unsigned long lastCommandTime = 0;
const unsigned long commandDelay = 1000;

// Gas Sensors
const int mq2Pin = A0;
const int mq7Pin = A1;

// Motor Control Functions
int motorSpeed = 150; // Initial speed

// DHT11 Sensor
const int dhtPin = 13;
#define DHTTYPE DHT11
DHT dht(dhtPin, DHTTYPE);

// NEO-6M GPS Module
SoftwareSerial gpsSerial(10, 11);
TinyGPSPlus gps;

// L298N Motor Driver Pins
const int in1Pin = 4;
const int in2Pin = 5;
const int in3Pin = 7;
const int in4Pin = 8;
const int enaPin = 3;
const int enbPin = 6;

// Beeper
const int beeperPin = 12;

// Thresholds for Gas Detection
const int mq2Threshold = 500;
const int mq7Threshold = 600;

// Beeper Frequency and Duration
const int beeperFreq = 1500;
const int beeperDuration = 1000;

// Robot State Variables
bool gasDetected = false;
String lastAlertMessage = "";
bool sendingData = false; // Flag to indicate if data is being sent

// Data sending interval (milliseconds)
const long dataInterval = 1000;
unsigned long lastDataTime = 0;

void readSensors() {
  // No need to read and print here anymore, read only when requested
  int mq2Value = analogRead(mq2Pin);
  int mq7Value = analogRead(mq7Pin);
  float humidity = dht.readHumidity();
  float temperature = dht.readTemperature();

  if (isnan(humidity) || isnan(temperature)) {
    Serial.println("Failed to read from DHT sensor!");
  }
  //gasDetected is updated here
  if (mq2Value > mq2Threshold || mq7Value > mq7Threshold) {
    gasDetected = true;
  } else {
    gasDetected = false;
  }
}

void processAlerts() {
  if (gasDetected) {
    Serial.println("Gas Detected!");
    tone(beeperPin, beeperFreq, beeperDuration);
    sendAlert("Gas Detected!");
  }
}

void sendAlert(String message) {
  // Send alert message via Bluetooth only if it's a new alert
  if (message != lastAlertMessage) {
    String alert = "ALERT:" + message; //changed
    BS.println(alert);
    Serial.print("Bluetooth Sent: ");
    Serial.println(alert);
    lastAlertMessage = message;
  }
}

void sendSensorData() {
  

    int mq2Value = analogRead(mq2Pin);
    int mq7Value = analogRead(mq7Pin);
    float humidity = dht.readHumidity();
    float temperature = dht.readTemperature();
    String gpsInfo = getGPSInfo();

    String sensorData = "DATA:MQ2Value:" + String(mq2Value)+"ppm"+",MQ7Value:" + String(mq7Value)+"ppm"+",Temp:"+String(temperature)+"C"+",Humidity:"+String(humidity)+"%"+","+gpsInfo;
    delay(1000);
    BS.println(sensorData);
    Serial.print("Bluetooth Sent Data: ");
    Serial.println(sensorData);
}

String getGPSInfo() {
  if (gps.location.isValid()) {
    return "Latitude:" + String(gps.location.lat(), 6) + ",Longitude:" + String(gps.location.lng(), 6);
  } else {
    return "Latitude:N/A,Longitude:N/A";
  }
}

String getDHTInfo() {
  float humidity = dht.readHumidity();
  float temperature = dht.readTemperature();
  if (isnan(humidity) || isnan(temperature)) {
    return "Humidity:N/A,Temp:N/A";
  } else {
    return "Humidity:" + String(humidity, 1) + ",Temp:" + String(temperature, 1);
  }
}

void processCommand(String command) { // Changed to String
  Serial.print("Received Bluetooth Command: ");
  Serial.println(command);

  if (command == "FORWARD") {
    forward();
  } else if (command == "BACKWARD") {
    backward();
  } else if (command == "LEFT") {
    left();
  } else if (command == "RIGHT") {
    right();
  } else if (command == "STOP") {
    stopMotors();
  } else if (command == "SPEEDUP") {
    increaseSpeed();
  } else if (command == "SPEEDDOWN") {
    decreaseSpeed();
  } else if (command == "GETDATA") { // New command to get sensor data
    sendingData = true; // Set the flag
    readSensors();      // Read the sensors immediately before sending
    sendSensorData();    // Send the data
    sendingData = false;
  } else {
    Serial.println("Unknown Command");
  }
}

void forward() {
  digitalWrite(in1Pin, HIGH);
  digitalWrite(in2Pin, LOW);
  digitalWrite(in3Pin, LOW);
  digitalWrite(in4Pin, HIGH);
  analogWrite(enaPin, motorSpeed);
  analogWrite(enbPin, motorSpeed);
}

void backward() {
  digitalWrite(in1Pin, LOW);
  digitalWrite(in2Pin, HIGH);
  digitalWrite(in3Pin, HIGH);
  digitalWrite(in4Pin, LOW);
  analogWrite(enaPin, motorSpeed);
  analogWrite(enbPin, motorSpeed);
}

void left() {
  digitalWrite(in1Pin, LOW);
  digitalWrite(in2Pin, HIGH);
  digitalWrite(in3Pin, LOW);
  digitalWrite(in4Pin, HIGH);
  analogWrite(enaPin, motorSpeed);
  analogWrite(enbPin, motorSpeed);
}

void right() {
  digitalWrite(in1Pin, HIGH);
  digitalWrite(in2Pin, LOW);
  digitalWrite(in3Pin, HIGH);
  digitalWrite(in4Pin, LOW);
  analogWrite(enaPin, motorSpeed);
  analogWrite(enbPin, motorSpeed);
}

void stopMotors() {
  digitalWrite(in1Pin, LOW);
  digitalWrite(in2Pin, LOW);
  digitalWrite(in3Pin, LOW);
  digitalWrite(in4Pin, LOW);
  analogWrite(enaPin, 0);
  analogWrite(enbPin, 0);
}

void increaseSpeed() {
  motorSpeed = min(255, motorSpeed + 20);
  Serial.print("Speed increased to: ");
  Serial.println(motorSpeed);
}

void decreaseSpeed() {
  motorSpeed = max(0, motorSpeed - 20);
  Serial.print("Speed decreased to: ");
  Serial.println(motorSpeed);
}

void readGPS() {
  while (gpsSerial.available() > 0) {
    if (gps.encode(gpsSerial.read())) {
    }
  }
}

void setup() {
  BS.begin(9600);
  Serial.begin(4800);

  //gpsSerial.begin(4800);
  dht.begin();
  pinMode(beeperPin, OUTPUT);

  // Motor Driver Pins as Outputs
  pinMode(in1Pin, OUTPUT);
  pinMode(in2Pin, OUTPUT);
  pinMode(in3Pin, OUTPUT);
  pinMode(in4Pin, OUTPUT);
  pinMode(enaPin, OUTPUT);
  pinMode(enbPin, OUTPUT);
  stopMotors();
  delay(3000);

  Serial.println("Gas Detection Robot Initialized!");
}

void loop() {
  readGPS();
  readSensors();  //keep reading sensors.
  processAlerts(); //check for alerts

  if (BS.available()) {
    String command = BS.readStringUntil('\n'); // Read until newline
    command.trim(); // Remove leading/trailing spaces
    if (!sendingData) { // Only process commands if not sending data
      processCommand(command);
    }
  }
  if (sendingData) {
    sendSensorData();
  }
}