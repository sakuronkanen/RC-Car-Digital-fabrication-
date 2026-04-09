from machine import Pin, PWM
import bluetooth, struct
from micropython import const

# =========================
# BLE NUS UUIDs (must match Android app)
# =========================
_UART_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_TX   = (bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), bluetooth.FLAG_NOTIFY)
_UART_RX   = (bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
              bluetooth.FLAG_WRITE | bluetooth.FLAG_WRITE_NO_RESPONSE)
_UART_SERVICE = (_UART_UUID, (_UART_TX, _UART_RX))

_IRQ_CENTRAL_CONNECT    = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)
_IRQ_GATTS_WRITE        = const(3)

MOTOR_IN1_PIN = 2
MOTOR_IN2_PIN = 3
MOTOR_PWM_PIN = 9

in1 = Pin(MOTOR_IN1_PIN, Pin.OUT)
in2 = Pin(MOTOR_IN2_PIN, Pin.OUT)

pwm = PWM(Pin(MOTOR_PWM_PIN))
pwm.freq(20000)  # 20kHz is quiet for DC motors (you can use 1000 too)


def advertising_payload(name="Pico_Car", services=None):
    payload = bytearray()

    def _append(adv_type, value):
        payload.extend(struct.pack("BB", len(value) + 1, adv_type) + value)

    _append(0x01, b"\x06")          # flags
    _append(0x09, name.encode())    # complete local name

    if services:
        for uuid in services:
            b = bytes(uuid)
            if len(b) == 16:
                _append(0x07, b)    # complete list of 128-bit UUIDs
    return payload


# =========================
# Servo setup (GPIO 1)
# =========================
servo = PWM(Pin(1))
servo.freq(50)  # 50Hz => 20ms period

def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v

SERVO_CENTER = 4800

# Pick conservative limits first; widen later if needed.
SERVO_MIN = 2000
SERVO_MAX = 5500

# How many duty counts per 1 joystick unit.
# With these limits: range each side = min(4800-2000, 5500-4800) = 700
# scale ~ 700/100 = 7
SERVO_SCALE = 7

def set_servo_from_x(x):
    # x expected -100..100 (int)
    x = clamp(x, -100, 100)

    duty = SERVO_CENTER + (x * SERVO_SCALE)
    duty = clamp(duty, SERVO_MIN, SERVO_MAX)

    servo.duty_u16(duty)

# center servo at startup
set_servo_from_x(0)


def motor_stop():
    in1.value(0)
    in2.value(0)
    pwm.duty_u16(0)

def motor_set_from_y(y):
    """
    y: joystick Y in range -100..100
      +y => forward
      -y => reverse
    """
    y = clamp(y, -100, 100)

    # small deadzone so motor doesn't whine near 0
    if -5 < y < 5:
        motor_stop()
        return

    forward = (y > 0)
    speed = abs(y)  # 0..100

    # direction pins
    if forward:
        in1.value(1)
        in2.value(0)
    else:
        in1.value(0)
        in2.value(1)

    # map 0..100 -> 0..65535
    duty = int(speed * 65535 / 100)
    pwm.duty_u16(duty)


# =========================
# BLE NUS Peripheral
# =========================
class NUS:
    def __init__(self, ble):
        self._ble = ble
        self._ble.active(True)
        self._ble.irq(self._irq)

        ((self._tx_handle, self._rx_handle),) = self._ble.gatts_register_services((_UART_SERVICE,))
        self._conn_handle = None

        self._payload = advertising_payload(name="Pico_Car", services=[_UART_UUID])
        self._advertise()

    def _advertise(self):
        self._ble.gap_advertise(100_000, adv_data=self._payload)  # 100ms
        print("Advertising...")

    def _irq(self, event, data):
        if event == _IRQ_CENTRAL_CONNECT:
            self._conn_handle, _, _ = data
            print("Connected:", self._conn_handle)

        elif event == _IRQ_CENTRAL_DISCONNECT:
            print("Disconnected")
            self._conn_handle = None
            self._advertise()

        elif event == _IRQ_GATTS_WRITE:
            conn_handle, value_handle = data
            if value_handle == self._rx_handle:
                raw = self._ble.gatts_read(self._rx_handle)
                if len(raw) >= 2:
                    x = struct.unpack("b", raw[0:1])[0]
                    y = struct.unpack("b", raw[1:2])[0]
                    # Use ONLY X to control servo
                    set_servo_from_x(x)
                    motor_set_from_y(y)
                    print("Joystick x,y:", x, y)

    def notify_text(self, s: str):
        if self._conn_handle is None:
            return
        self._ble.gatts_notify(self._conn_handle, self._tx_handle, s.encode())

ble = bluetooth.BLE()
nus = NUS(ble)

# No while True needed: BLE IRQ drives updates
# (Optional: you can sleep to reduce CPU use)
import time
while True:
    time.sleep(1)
