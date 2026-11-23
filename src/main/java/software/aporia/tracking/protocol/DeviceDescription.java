package software.aporia.tracking.protocol;

/** Metadata evidenced by a validated frame. The model is optional. */
public record DeviceDescription(String manufacturer, String deviceType, String wireFormat, String reportedModel) {}
