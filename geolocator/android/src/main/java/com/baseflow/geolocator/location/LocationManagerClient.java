package com.baseflow.geolocator.location;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;
import com.google.android.gms.common.util.Strings;

import java.util.List;

class LocationManagerClient implements LocationClient, LocationListener {

  private static final long TWO_MINUTES = 120000;
  public Context context;
  private final LocationManager locationManager;
  @Nullable private final LocationOptions locationOptions;

  private boolean isListening = false;

  @Nullable private Location currentBestLocation;
  @Nullable private String currentLocationProvider;
  @Nullable private PositionChangedCallback positionChangedCallback;
  @Nullable private ErrorCallback errorCallback;

  public LocationManagerClient(
      @NonNull Context context, @Nullable LocationOptions locationOptions) {
    this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    this.locationOptions = locationOptions;
    this.context = context;
  }

  @Override
  public void isLocationServiceEnabled(LocationServiceListener listener) {
    if (locationManager == null) {
      listener.onLocationServiceResult(false);
      return;
    }

    listener.onLocationServiceResult(checkLocationService(context));
  }

  @Override
  public void getLastKnownPosition(
      PositionChangedCallback positionChangedCallback, ErrorCallback errorCallback) {
    Location bestLocation = null;

    for (String provider : locationManager.getProviders(true)) {

      @SuppressLint("MissingPermission")
      Location location = locationManager.getLastKnownLocation(provider);
//        Log.i("zzb", "getLastKnownPosition provider "+ provider + " getLatitude = " + location.getLatitude() + " getLongitude = " + location.getLongitude());

      if (location != null && isBetterLocation(location, bestLocation)) {
        bestLocation = location;
      }
    }

    positionChangedCallback.onPositionChanged(bestLocation);
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode) {
    return false;
  }

  @SuppressLint("MissingPermission")
  @Override
  public void startPositionUpdates(
      Activity activity,
      PositionChangedCallback positionChangedCallback,
      ErrorCallback errorCallback) {

      Log.i("zzb", "LocationManagerClient startPositionUpdates");
    if (!checkLocationService(context)) {
      errorCallback.onError(ErrorCodes.locationServicesDisabled);
      return;
    }

    this.positionChangedCallback = positionChangedCallback;
    this.errorCallback = errorCallback;

    LocationAccuracy locationAccuracy =
        this.locationOptions != null ? this.locationOptions.getAccuracy() : LocationAccuracy.best;

    this.currentLocationProvider = getBestProvider(this.locationManager, locationAccuracy);

    if (Strings.isEmptyOrWhitespace(this.currentLocationProvider)) {
      errorCallback.onError(ErrorCodes.locationServicesDisabled);
      return;
    }

    long timeInterval = 0;
    float distanceFilter = 0;
    if (this.locationOptions != null) {
      timeInterval = locationOptions.getTimeInterval();
      distanceFilter = locationOptions.getDistanceFilter();
    }

    this.isListening = true;
    this.locationManager.requestLocationUpdates(
        this.currentLocationProvider, timeInterval, distanceFilter, this, Looper.getMainLooper());
  }

  @SuppressLint("MissingPermission")
  @Override
  public void stopPositionUpdates() {
    this.isListening = false;
    this.locationManager.removeUpdates(this);
  }

  @Override
  public synchronized void onLocationChanged(Location location) {
      Log.i("zzb", "onLocationChanged ???????????? " + " getLatitude = " + location.getLatitude() + " getLongitude = " + location.getLongitude());
    float desiredAccuracy =
        locationOptions != null ? accuracyToFloat(locationOptions.getAccuracy()) : 50;
      Log.i("zzb", "location.getAccuracy ???????????? " + location.getAccuracy() + " desiredAccuracy = " + desiredAccuracy);

    if (isBetterLocation(location, currentBestLocation)
        && location.getAccuracy() <= desiredAccuracy) {
        Log.i("zzb", "onLocationChanged ?????????Flutter ");
      this.currentBestLocation = location;

      if (this.positionChangedCallback != null) {
        this.positionChangedCallback.onPositionChanged(currentBestLocation);
      }
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {
      Log.i("zzb", "onStatusChanged status = " + status);
    if (status == LocationProvider.AVAILABLE) {
      onProviderEnabled(provider);
    } else if (status == LocationProvider.OUT_OF_SERVICE) {
      onProviderDisabled(provider);
    }
  }

  @Override
  public void onProviderEnabled(String provider) {}

  @SuppressLint("MissingPermission")
  @Override
  public void onProviderDisabled(String provider) {
    if (provider.equals(this.currentLocationProvider)) {
      if (isListening) {
        this.locationManager.removeUpdates(this);
      }

      if (this.errorCallback != null) {
        errorCallback.onError(ErrorCodes.locationServicesDisabled);
      }

      this.currentLocationProvider = null;
    }
  }

  static boolean isBetterLocation(Location location, Location bestLocation) {
    if (bestLocation == null) return true;

    long timeDelta = location.getTime() - bestLocation.getTime();
    boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
    boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
    boolean isNewer = timeDelta > 0;

    if (isSignificantlyNewer) return true;

    if (isSignificantlyOlder) return false;

    float accuracyDelta = (int) (location.getAccuracy() - bestLocation.getAccuracy());
    boolean isLessAccurate = accuracyDelta > 0;
    boolean isMoreAccurate = accuracyDelta < 0;
    boolean isSignificantlyLessAccurate = accuracyDelta > 200;

    boolean isFromSameProvider = false;
    if (location.getProvider() != null) {
      isFromSameProvider = location.getProvider().equals(bestLocation.getProvider());
    }

    if (isMoreAccurate) return true;

    if (isNewer && !isLessAccurate) return true;

    //noinspection RedundantIfStatement
    if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return true;

    return false;
  }

  private static String getBestProvider(
      LocationManager locationManager, LocationAccuracy accuracy) {
    Criteria criteria = new Criteria();

    criteria.setBearingRequired(false);
    criteria.setAltitudeRequired(false);
    criteria.setSpeedRequired(false);

    switch (accuracy) {
      case lowest:
        criteria.setAccuracy(Criteria.NO_REQUIREMENT);
        criteria.setHorizontalAccuracy(Criteria.NO_REQUIREMENT);
        criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
        break;
      case low:
        criteria.setAccuracy(Criteria.ACCURACY_COARSE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_LOW);
        criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
        break;
      case medium:
        criteria.setAccuracy(Criteria.ACCURACY_COARSE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_MEDIUM);
        criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
        break;
      default:
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
        criteria.setPowerRequirement(Criteria.POWER_HIGH);
        break;
    }

    String provider = locationManager.getBestProvider(criteria, true);

      //????????????provider????????????????????????????????????
    Location currentLocation = locationManager.getLastKnownLocation(provider);
      Log.i("zzb", "getBestProvider ?????? =" + provider + " ?????? =  " + currentLocation);

//    if (Strings.isEmptyOrWhitespace(provider)) {
      List<String> providers = locationManager.getProviders(true);
      
        for (int i = 0; i < providers.size(); i++) {
            Log.i("zzb", "providers ?????? =" + providers.get(i));
        }
//      if (providers.size() > 0) provider = providers.get(0);
      if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
          provider = LocationManager.NETWORK_PROVIDER;
      } else if (providers.contains(LocationManager.GPS_PROVIDER)) {
          provider = LocationManager.GPS_PROVIDER;
      }
//    }

    return provider;
  }

  private static float accuracyToFloat(LocationAccuracy accuracy) {
    switch (accuracy) {
      case lowest:
        return 1000;
      case low:
        return 500;
      case medium:
        return 250;
      case best:
      case bestForNavigation:
        return 50;
      default:
        return 100;
    }
  }
}
