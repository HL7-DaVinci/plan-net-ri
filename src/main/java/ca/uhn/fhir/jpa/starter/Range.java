package ca.uhn.fhir.jpa.starter;

import java.math.BigDecimal;

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

    public Range(BigDecimal latitude, BigDecimal longitude, BigDecimal radius, DistanceUnit unit){
        setLatitude(latitude);
        setLongitude(longitude);
        this.radius = radius == null || radius.doubleValue() == 0.0 ? 25.0 : radius.doubleValue();
        this.unit = unit;
        setDistanceMultiplier();
    }

    public  Range(BigDecimal latitude, BigDecimal longitude, BigDecimal radius, String unit){
        this(latitude, longitude, radius, cleanUnit(unit));
    }

    // much help from http://janmatuschek.de/LatitudeLongitudeBoundingCoordinates
    public boolean encompasses(Location location){
        double paramLat = Math.toRadians(location.getPosition().getLatitude().doubleValue());
        double paramLon = Math.toRadians(location.getPosition().getLongitude().doubleValue());

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

    public void setLatitude(BigDecimal latitude) {
        setLatitude(latitude.doubleValue());
    }

    public double getLongitude() {
        return this.longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = Math.toRadians(longitude);
    }

    public void setLongitude(BigDecimal longitude) {
        setLongitude(longitude.doubleValue());
    }

    public double getRadius() {
        return this.radius;
    }

    public void setRadius(double radius) {
        this.radius = radius == 0.0 ? 25.0 : radius;
        setDistanceMultiplier();
    }

    public void setRadius(BigDecimal radius) {
        this.radius = radius == null || radius.doubleValue() == 0.0 ? 25.0 : radius.doubleValue();
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