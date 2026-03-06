package net.vulkanmod.mixin.profiling;

import com.google.common.base.Stopwatch;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.TelemetryEventType;
import net.minecraft.client.telemetry.TelemetryProperty;
import net.minecraft.client.telemetry.TelemetryPropertyMap;
import net.minecraft.client.telemetry.events.GameLoadTimesEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

@Mixin(GameLoadTimesEvent.class)
public class GameLoadTimeEventM {

    @Shadow @Final private Map<TelemetryProperty<GameLoadTimesEvent.Measurement>, Stopwatch> measurements;

    @Shadow @Final private static Logger LOGGER;

    @Shadow private OptionalLong bootstrapTime;

    public void send(TelemetryEventSender telemetryEventSender) {
        Map<TelemetryProperty, GameLoadTimesEvent.Measurement> measurements = new Reference2ReferenceOpenHashMap<>();

        synchronized (this) {
            this.measurements
                    .forEach(
                            (telemetryProperty, stopwatch) -> {
                                if (!stopwatch.isRunning()) {
                                    long l = stopwatch.elapsed(TimeUnit.MILLISECONDS);
                                    measurements.put(telemetryProperty, new GameLoadTimesEvent.Measurement((int)l));
                                } else {
                                    LOGGER.warn(
                                            "Measurement {} was discarded since it was still ongoing when the event {} was sent.",
                                            telemetryProperty.id(),
                                            TelemetryEventType.GAME_LOAD_TIMES.id()
                                    );
                                }
                            }
                    );
            this.bootstrapTime.ifPresent(l -> measurements.put(TelemetryProperty.LOAD_TIME_BOOTSTRAP_MS, new GameLoadTimesEvent.Measurement((int)l)));
            this.measurements.clear();
        }

        StringBuilder stringBuilder = new StringBuilder("\n");

        for (TelemetryProperty property : measurements.keySet()) {
            var measurement = measurements.get(property);

            stringBuilder.append("%s: %sms\n".formatted(property.id(), measurement.millis()));
        }

        LOGGER.info(stringBuilder.toString());

//        telemetryEventSender.send(
//                TelemetryEventType.GAME_LOAD_TIMES,
//                builder -> {
//                    synchronized (this) {
//                        this.measurements
//                                .forEach(
//                                        (telemetryProperty, stopwatch) -> {
//                                            if (!stopwatch.isRunning()) {
//                                                long l = stopwatch.elapsed(TimeUnit.MILLISECONDS);
//                                                builder.put(telemetryProperty, new GameLoadTimesEvent.Measurement((int)l));
//                                            } else {
//                                                LOGGER.warn(
//                                                        "Measurement {} was discarded since it was still ongoing when the event {} was sent.",
//                                                        telemetryProperty.id(),
//                                                        TelemetryEventType.GAME_LOAD_TIMES.id()
//                                                );
//                                            }
//                                        }
//                                );
//                        this.bootstrapTime.ifPresent(l -> builder.put(TelemetryProperty.LOAD_TIME_BOOTSTRAP_MS, new GameLoadTimesEvent.Measurement((int)l)));
//                        this.measurements.clear();
//                    }
//                }
//        );
    }
}
