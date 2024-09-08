package com.vagell.kv4pht.aprs.parser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public class WeatherField extends APRSData {
    private Integer windDirection;
    private Integer windSpeed;
    private Integer windGust;
    private Integer temp;
    private Double rainLastHour;
    private Double rainLast24Hours;
    private Double rainSinceMidnight;
    private Double humidity;
    private Double pressure;
    private Integer luminosity;
    private Double snowfallLast24Hours;
    private Integer rawRainCounter;

    
    /** 
     * @param d
     * @param decimalPlace
     * @return Double
     */
    public static Double round(Double d, int decimalPlace) {
        if ( d == null ) return null;
        BigDecimal bd = new BigDecimal(Double.toString(d));
        bd = bd.setScale(decimalPlace, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
    
    
    /** 
     * @return String
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("---WEATHER---\n");
        sb.append("Wind Direction:\t" + this.windDirection + "\n");
        sb.append("Wind Speed:\t" + this.windSpeed + "\n");
        sb.append("Wind Gust:\t" + this.windGust + "\n");
        sb.append("Temperature:\t" + this.temp + "\n");
        sb.append("Rain last hour:\t" + this.rainLastHour + "\n");
        sb.append("Rain 24 hours:\t" + this.rainLast24Hours + "\n");
        sb.append("Rain since 00:00:\t" + this.rainSinceMidnight + "\n");
        sb.append("Humidity:\t" + this.humidity + "\n");
        sb.append("Pressure:\t" + this.pressure + "\n");
        return sb.toString();
    }

    
    /** 
     * @return Integer
     */
    public Integer getWindDirection() {
        return this.windDirection;
    }

    
    /** 
     * @param windDirection
     */
    public void setWindDirection(Integer windDirection) {
        this.windDirection = windDirection;
    }

    
    /** 
     * @return Integer
     */
    public Integer getWindSpeed() {
        return this.windSpeed;
    }

    
    /** 
     * @param windSpeed
     */
    public void setWindSpeed(Integer windSpeed) {
        this.windSpeed = windSpeed;
    }

    
    /** 
     * @return Integer
     */
    public Integer getWindGust() {
        return this.windGust;
    }

    
    /** 
     * @param windGust
     */
    public void setWindGust(Integer windGust) {
        this.windGust = windGust;
    }

    
    /** 
     * @return Integer
     */
    public Integer getTemp() {
        return this.temp;
    }

    
    /** 
     * @param temp
     */
    public void setTemp(Integer temp) {
        this.temp = temp;
    }

    
    /** 
     * @return Double
     */
    public Double getRainLastHour() {
        return round(this.rainLastHour, 2);
    }

    
    /** 
     * @param rainLastHour
     */
    public void setRainLastHour(Double rainLastHour) {
        this.rainLastHour = rainLastHour;
    }

    
    /** 
     * @return Double
     */
    public Double getRainLast24Hours() {
        return round(this.rainLast24Hours,2);
    }

    
    /** 
     * @param rainLast24Hours
     */
    public void setRainLast24Hours(Double rainLast24Hours) {
        this.rainLast24Hours = rainLast24Hours;
    }

    
    /** 
     * @return Double
     */
    public Double getRainSinceMidnight() {
        return round(this.rainSinceMidnight,2);
    }

    
    /** 
     * @param rainSinceMidnight
     */
    public void setRainSinceMidnight(Double rainSinceMidnight) {
        this.rainSinceMidnight = rainSinceMidnight;
    }

    
    /** 
     * @return Double
     */
    public Double getHumidity() {
        return round(this.humidity,2);
    }

    
    /** 
     * @param humidity
     */
    public void setHumidity(Double humidity) {
        this.humidity = humidity;
    }

    
    /** 
     * @return Double
     */
    public Double getPressure() {
        return this.pressure;
    }

    
    /** 
     * @param pressure
     */
    public void setPressure(Double pressure) {
        this.pressure = pressure;
    }

    
    /** 
     * @return Integer
     */
    public Integer getLuminosity() {
        return this.luminosity;
    }

    
    /** 
     * @param luminosity
     */
    public void setLuminosity(Integer luminosity) {
        this.luminosity = luminosity;
    }

    
    /** 
     * @return Double
     */
    public Double getSnowfallLast24Hours() {
        return round(this.snowfallLast24Hours,1);
    }

    
    /** 
     * @param snowfallLast24Hours
     */
    public void setSnowfallLast24Hours(Double snowfallLast24Hours) {
        this.snowfallLast24Hours = snowfallLast24Hours;
    }

    
    /** 
     * @return Integer
     */
    public Integer getRawRainCounter() {
        return this.rawRainCounter;
    }

    
    /** 
     * @param rawRainCounter
     */
    public void setRawRainCounter(Integer rawRainCounter) {
        this.rawRainCounter = rawRainCounter;
    }

    
    /** 
     * @param o
     * @return int
     */
    @Override
    public int compareTo(APRSData o) {
        if (this.hashCode() > o.hashCode()) {
            return 1;
        }
        if (this.hashCode() == o.hashCode()) {
            return 0;
        }
        return -1;
    }

    
    /** 
     * @return boolean
     */
    @Override
    public boolean hasFault() {
        return this.hasFault;
    }

    
    /** 
     * @param o
     * @return boolean
     */
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof WeatherField)) {
            return false;
        }
        WeatherField weatherField = (WeatherField) o;
        return Objects.equals(windDirection, weatherField.windDirection)
                && Objects.equals(windSpeed, weatherField.windSpeed) && Objects.equals(windGust, weatherField.windGust)
                && Objects.equals(temp, weatherField.temp) && Objects.equals(rainLastHour, weatherField.rainLastHour)
                && Objects.equals(rainLast24Hours, weatherField.rainLast24Hours)
                && Objects.equals(rainSinceMidnight, weatherField.rainSinceMidnight)
                && Objects.equals(humidity, weatherField.humidity) && Objects.equals(pressure, weatherField.pressure)
                && Objects.equals(luminosity, weatherField.luminosity)
                && Objects.equals(snowfallLast24Hours, weatherField.snowfallLast24Hours)
                && Objects.equals(rawRainCounter, weatherField.rawRainCounter);
    }

    
    /** 
     * @return int
     */
    @Override
    public int hashCode() {
        return Objects.hash(windDirection, windSpeed, windGust, temp, rainLastHour, rainLast24Hours, rainSinceMidnight,
                humidity, pressure, luminosity, snowfallLast24Hours, rawRainCounter);
    }

}