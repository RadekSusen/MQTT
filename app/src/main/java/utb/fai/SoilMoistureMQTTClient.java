package utb.fai;

import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import utb.fai.API.HumiditySensor;
import utb.fai.API.IrrigationSystem;

import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Timer;

/**
 * Trida MQTT klienta pro mereni vhlkosti pudy a rizeni zavlazovaciho systemu.
 * 
 * V teto tride implementuje MQTT klienta
 */
public class SoilMoistureMQTTClient {

    private MqttClient client;
    private HumiditySensor humiditySensor;
    private IrrigationSystem irrigationSystem;
    private ScheduledExecutorService scheduler;
    private Timer timer;

    /**
     * Vytvori instacni tridy MQTT klienta pro mereni vhlkosti pudy a rizeni
     * zavlazovaciho systemu
     * 
     * @param sensor     Senzor vlhkosti
     * @param irrigation Zarizeni pro zavlahu pudy
     */
    public SoilMoistureMQTTClient(HumiditySensor sensor, IrrigationSystem irrigation) {
        this.humiditySensor = sensor;
        this.irrigationSystem = irrigation;
    }

    /**
     * Metoda pro spusteni klienta
     */
    public void start() {
        try {
            client = new MqttClient(Config.BROKER, Config.CLIENT_ID);
            client.connect();
            client.subscribe(Config.TOPIC_IN, this::handleMessage);

            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::monitor, 0, 10, TimeUnit.SECONDS);

            timer = new Timer();
        } catch (MqttException e) {
            e.printStackTrace();
        }

    }

    private void handleMessage(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());

        switch (payload) {
            case Config.REQUEST_GET_HUMIDITY:
                handleHumidity();
                break;
            case Config.REQUEST_GET_STATUS:
                handleStatus();
                break;
            case Config.REQUEST_START_IRRIGATION:
                handleStart();
                break;
            case Config.REQUEST_STOP_IRRIGATION:
                handleStop();
                break;
            default:
                System.out.println("Unknown request: " + payload);
        }
    }

    private void handleHumidity() {
        if (humiditySensor.hasFault()) {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";" + "HUMIDITY_SENSOR");
        } else {
            float humidity = humiditySensor.readRAWValue();
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_HUMIDITY + ";" + humidity);
        }
    }

    private void handleStatus() {
        String status = irrigationSystem.isActive() ? "irrigation_on" : "irrigation_off";
        if (irrigationSystem.hasFault()) {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";" + "IRRIGATION_SYSTEM");
        } else {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_STATUS + ";" + status);
        }
    }

    private void handleStart() {
        irrigationSystem.activate();
        if (irrigationSystem.hasFault()) {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";" + "IRRIGATION_SYSTEM");
        } else {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_STATUS + ";irrigation_on");
            resetTimer();
        }
    }

    private void handleStop() {
        irrigationSystem.deactivate();
        if (irrigationSystem.hasFault()) {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";" + "IRRIGATION_SYSTEM");
        } else {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_STATUS + ";irrigation_off");
            timer.cancel();
        }
    }

    private void monitor() {
        boolean humiditySensorError = humiditySensor.hasFault();
        boolean irrigationSystemError = irrigationSystem.hasFault();

        if (humiditySensorError) {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";HUMIDITY_SENSOR");
        }

        if (irrigationSystemError) {
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";IRRIGATION_SYSTEM");
        }

        // If no faults, send humidity data
        if (!humiditySensorError && !irrigationSystemError) {
            float humidity = humiditySensor.readRAWValue();
            sendMess(Config.TOPIC_OUT, Config.RESPONSE_HUMIDITY + ";" + humidity);
        }

        /**
         * boolean error = false;
         * 
         * if (humiditySensor.hasFault()) {
         * sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";HUMIDITY_SENSOR");
         * error = true;
         * }
         * if (irrigationSystem.hasFault()) {
         * sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";IRRIGATION_SYSTEM");
         * error = true;
         * }
         * if (!error) {
         * float humidity = humiditySensor.readRAWValue();
         * sendMess(Config.TOPIC_OUT, Config.RESPONSE_HUMIDITY + ";" + humidity);
         * }
         * 
         */
    }

    private void resetTimer() {
        timer.cancel();
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (irrigationSystem.hasFault()) {
                    sendMess(Config.TOPIC_OUT, Config.RESPONSE_FAULT + ";IRRIGATION_SENSOR");
                } else {
                    sendMess(Config.TOPIC_OUT, Config.REQUEST_GET_STATUS + ";irrigation_off");
                }
            }
        }, 3000);
    }

    private void sendMess(String topic, String payload) {
        try {
            client.publish(topic, new MqttMessage(payload.getBytes()));
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}
