from machine import Pin, PWM, ADC
import time

# Servo PWM
servo = PWM(Pin(1))
servo.freq(50)  # Servo käyttää 50 Hz

# Potentiometri ADC
pot = ADC(Pin(26))   # Pico: GP26 (ADC0)

while True:
    value = pot.read_u16()   # 0–65535

    # Muunnetaan servo PWM duty arvoon
    # Servo pulssi ~1ms–2ms (duty n. 1638–8192 Pico PWM:llä)
    
    print(value)
    
    if 47500 < value <= 51500:
        servo.duty_u16(4800)
    elif value <= 48000:
        servo.duty_u16(5500)
    else:
        servo.duty_u16(2000)
    time.sleep(0.02)