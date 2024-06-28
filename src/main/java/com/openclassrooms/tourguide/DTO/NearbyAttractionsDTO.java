package com.openclassrooms.tourguide.DTO;

public record NearbyAttractionsDTO(String attractionName, double attractionLongitude, double attractionLatitude,
		double userLongitude, double userLatitude, double distance, int points) {

}
