package com.openclassrooms.tourguide.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;

@Service
public class RewardsService {

	private static final Logger logger = LogManager.getLogger("rewardsService");

	private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	private final ExecutorService executor = Executors.newFixedThreadPool(200);

	// proximity in miles
	private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();
		List<Attraction> attractions = gpsUtil.getAttractions();

		// Liste pour stocker toutes les tâches asynchrones
		List<CompletableFuture<Void>> futures = userLocations.stream().flatMap(
				visitedLocation -> attractions.stream().map(attraction -> calculate(user, attraction, visitedLocation)))
				.collect(Collectors.toList());

		// Attendre que toutes les tâches soient terminées
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	private CompletableFuture<Void> calculate(User user, Attraction attraction, VisitedLocation visitedLocation) {
		return CompletableFuture.supplyAsync(() -> {
			if (user.getUserRewards().stream()
					.noneMatch(r -> r.attraction.attractionName.equals(attraction.attractionName))) {
				if (nearAttraction(visitedLocation, attraction)) {
					UserReward userReward = new UserReward(visitedLocation, attraction,
							getRewardPoints(attraction, user));
					if (user.getUserRewards().stream()
							.noneMatch(r -> r.attraction.attractionName.equals(userReward.attraction.attractionName))) {
						synchronized (user) { // Synchronize on the user to avoid concurrent modification issues
							user.getUserRewards().add(userReward);
						}
					}
				}
			}
			return null;
		}, executor); // Use executor

	}

	public void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
	}

	private int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}

	public double getDistance(Location loc1, Location loc2) {
		double lat1 = Math.toRadians(loc1.latitude);
		double lon1 = Math.toRadians(loc1.longitude);
		double lat2 = Math.toRadians(loc2.latitude);
		double lon2 = Math.toRadians(loc2.longitude);

		double angle = Math
				.acos(Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

		double nauticalMiles = 60 * Math.toDegrees(angle);
		double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
		return statuteMiles;
	}

	public void calculateAllUsersRewards(List<User> allUsers) {
		allUsers.parallelStream().forEach(u -> calculateRewards(u));

	}

}
