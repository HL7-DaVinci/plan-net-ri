package ca.uhn.fhir.jpa.starter;

import org.hl7.fhir.r4.model.Location;

public class Range {

    public enum DistanceUnit {
        KILOMETERS, METERS, MILES, FEET;
    }

    private double latitude;
    private double longitude;
    private double radius;
    private DistanceUnit unit;
    private double distanceMultiplier;

    public Range(double latitude, double longitude, double radius, DistanceUnit unit){
        setLatitude(latitude);
        setLongitude(longitude);
        this.radius = radius == 0.0 ? 25.0 : radius;
        this.unit = unit;
        setDistanceMultiplier();
    }

    public Range(double latitude, double longitude, double radius, String unit){
        this(latitude, longitude, radius, cleanUnit(unit));
    }

    /**
     * @param params 2..*, params[0] -> lat, params[1] -> lon, params[2] -> rad, params[3] -> unit
     */
    public Range(String[] params) {
        setLatitude(Double.parseDouble(params[0]));
        setLongitude(Double.parseDouble(params[1]));

        double radius = 25.0;
        try {
            if (params.length > 2) radius = Double.parseDouble(params[2]);
            if (radius == 0.0) radius = 25.0;
        } catch (Exception e) {
            radius = 25.0;
        }
        this.radius = radius;

        String unit = "km";
        if (params.length > 3) unit = params[3];
        this.unit = cleanUnit(unit);

        setDistanceMultiplier();
    }

    // much help from http://janmatuschek.de/LatitudeLongitudeBoundingCoordinates
    public boolean encompasses(Location location){
        if (location == null) return false;
        Location.LocationPositionComponent pos = location.getPosition();
        if (pos == null || pos.getLatitude() == null || pos.getLongitude() == null) return false;
        double paramLat = Math.toRadians(pos.getLatitude().doubleValue());
        double paramLon = Math.toRadians(pos.getLongitude().doubleValue());

        //arccos(sin(lat1) · sin(lat2) + cos(lat1) · cos(lat2) · cos(lon1 - lon2)) · R
        double innerFirst = Math.sin(this.latitude) * Math.sin(paramLat);
        double innerSecond = Math.cos(this.latitude) * Math.cos(paramLat) * Math.cos(this.longitude - paramLon);
        double distance = Math.acos(innerFirst + innerSecond) * this.distanceMultiplier;
        return distance <= radius;
    }

    public double getLatitude() {
        return this.latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = Math.toRadians(latitude);
    }

    public double getLongitude() {
        return this.longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = Math.toRadians(longitude);
    }

    public double getRadius() {
        return this.radius;
    }

    public void setRadius(double radius) {
        this.radius = radius == 0.0 ? 25.0 : radius;
        setDistanceMultiplier();
    }

    public DistanceUnit getUnit() {
        return this.unit;
    }

    public void setUnit(String unit) {
        this.unit = cleanUnit(unit);
        setDistanceMultiplier();
    }

    public void setUnit(DistanceUnit unit) {
        this.unit = unit;
        setDistanceMultiplier();
    }

    private static DistanceUnit cleanUnit(String dirtyUnit){
        DistanceUnit cleanedUnit = DistanceUnit.KILOMETERS;
        if (dirtyUnit == null) {
            System.out.println("\nWARNING: UNIT PARAM IS NULL, DEFAULTS TO KILOMETERS");
        } else {
            switch (dirtyUnit.toLowerCase()) {
                case "mi":
                case "mis":
                case "mile":
                case "miles":
                    cleanedUnit = DistanceUnit.MILES;
                    break;
                case "km":
                case "kms":
                case "kilometer":
                case "kilometers":
                    cleanedUnit = DistanceUnit.KILOMETERS;
                    break;
                case "ft":
                case "fts":
                case "foot":
                case "feet":
                    cleanedUnit = DistanceUnit.FEET;
                    break;
                case "m":
                case "ms":
                case "meter":
                case "meters":
                    cleanedUnit = DistanceUnit.METERS;
                    break;
                default:
                    System.out.println("\nWARNING: UNEXPECTED INPUT \"" + dirtyUnit + "\", DEFAULTS TO KILOMETERS");
            }
        }
        return cleanedUnit;
    }

    private void setDistanceMultiplier() {
        final int EARTH_RADIUS_IN_KM = 6371;
        switch(this.unit) {
            case MILES:
                this.distanceMultiplier = EARTH_RADIUS_IN_KM * 0.621371;
                break;
            case KILOMETERS:
                this.distanceMultiplier = (double) EARTH_RADIUS_IN_KM;
                break;
            case METERS:
                this.distanceMultiplier = EARTH_RADIUS_IN_KM * 1000.0;
                break;
            case FEET:
                this.distanceMultiplier = EARTH_RADIUS_IN_KM * 3280.84;
                break;
        }
    }

}