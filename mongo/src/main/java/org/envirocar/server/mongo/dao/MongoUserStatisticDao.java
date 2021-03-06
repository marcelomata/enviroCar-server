/*
 * Copyright (C) 2013-2018 The enviroCar project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.envirocar.server.mongo.dao;

import java.util.function.Function;

import org.envirocar.server.core.DataService;
import org.envirocar.server.core.dao.UserStatisticDao;
import org.envirocar.server.core.entities.Measurements;
import org.envirocar.server.core.entities.Track;
import org.envirocar.server.core.entities.TrackSummaries;
import org.envirocar.server.core.entities.Tracks;
import org.envirocar.server.core.entities.UserStatistic;
import org.envirocar.server.core.filter.MeasurementFilter;
import org.envirocar.server.core.filter.TrackFilter;
import org.envirocar.server.core.filter.UserStatisticFilter;
import org.envirocar.server.core.util.pagination.PageBasedPagination;
import org.envirocar.server.mongo.MongoDB;
import org.envirocar.server.mongo.entity.MongoUser;
import org.envirocar.server.mongo.entity.MongoUserStatistic;
import org.envirocar.server.mongo.entity.MongoUserStatisticKey;
import org.envirocar.server.mongo.userstatistic.UserStatisticUpdateScheduler;
import org.envirocar.server.mongo.util.UserStatisticUtils;
import org.mongodb.morphia.dao.BasicDAO;
import org.mongodb.morphia.mapping.Mapper;

import com.google.inject.Inject;

/**
 * TODO JavaDoc
 *
 * @author Maurin Radtke <maurin.radtke@uni-muenster.de>
 */
public class MongoUserStatisticDao implements UserStatisticDao {

    public static final String ID = Mapper.ID_KEY;
    private final BasicDAO<MongoUserStatistic, MongoUserStatisticKey> dao;
    private final DataService dataService;
    private final MongoDB mongoDB;
    private final UserStatisticUpdateScheduler scheduler;

    // calculate on get() calculation function, if UserStatistics have not been created yet
    private final Function<UserStatisticFilter, MongoUserStatistic> calculateAllFunction = (UserStatisticFilter t) -> {
        return calculateAndSaveUserStatistic(t, key(t));
    };

    // update on New Track calculation function
    private final Function<Object[], MongoUserStatistic> calculateOnNewTrackFunction = (Object[] params) -> {
        UserStatisticFilter t = (UserStatisticFilter) params[0];
        Track track = (Track) params[1];
        return calculateAndSaveUserStatisticOnNewTrack(t, key(t), track);
    };

    // update on Track Deletion calculation function
    private final Function<Object[], MongoUserStatistic> calculateOnTrackDeletionFunction = (Object[] params) -> {
        UserStatisticFilter t = (UserStatisticFilter) params[0];
        Track track = (Track) params[1];
        Measurements measurements = (Measurements) params[2];
        return calculateAndSaveUserStatisticOnTrackDeletion(t, key(t), track, measurements);
    };

    @Inject
    public MongoUserStatisticDao(MongoDB mongoDB, DataService dataService, UserStatisticUpdateScheduler scheduler) {
        this.mongoDB = mongoDB;
        this.dao = new BasicDAO<>(
                MongoUserStatistic.class, mongoDB.getDatastore());
        this.scheduler = scheduler;
        this.dataService = dataService;
    }

    @Override
    public UserStatistic get(UserStatisticFilter request) {
        MongoUserStatisticKey key = key(request);
        MongoUserStatistic result = this.dao.get(key);
        if (result == null) {
            // calculate UserStatistics:
            this.scheduler.updateUserStatistic(request, key, calculateAllFunction, true);
            result = this.dao.get(key);
        }
        return result;
    }

    private MongoUserStatistic calculateAndSaveUserStatisticOnTrackDeletion(UserStatisticFilter request,
                                                                            MongoUserStatisticKey key, Track track,
                                                                            Measurements measurements) {
        // get previous:
        MongoUserStatistic v = this.dao.get(key);
        // calculate:
        v = new UserStatisticUtils().removeTrackStatistic(v, track, measurements);
        // persist:
        this.dao.save(v);
        return v;
    }

    private MongoUserStatistic calculateAndSaveUserStatisticOnNewTrack(UserStatisticFilter request,
                                                                       MongoUserStatisticKey key, Track track) {
        // get previous:
        MongoUserStatistic v = this.dao.get(key);
        // get measurements:
        Measurements values = dataService.getMeasurements(new MeasurementFilter(track));
        // calculate:
        v = new UserStatisticUtils().addTrackStatistic(v, track, values);
        // persist:
        this.dao.save(v);
        return v;
    }

    private MongoUserStatistic calculateAndSaveUserStatistic(UserStatisticFilter request, MongoUserStatisticKey key) {
        MongoUserStatistic v = new MongoUserStatistic(key);
        v.setTrackSummaries(new TrackSummaries());

        // calculate it:
        Tracks tracks = dataService.getTracks(new TrackFilter(request.getUser(), new PageBasedPagination(10000, 1))
        );
        Iterable<Track> userTracks = Tracks.from(tracks).build();
        for (Track track : userTracks) { // get all measurments of this track:
            Measurements values = dataService.getMeasurements(new MeasurementFilter(track));
            v = new UserStatisticUtils().addTrackStatistic(v, track, values);
        }
        // persist:
        this.dao.save(v);
        return v;
    }

    private MongoUserStatisticKey key(UserStatisticFilter request) {
        MongoUser user = (MongoUser) request.getUser();
        return new MongoUserStatisticKey(mongoDB.key(user));
    }

    @Override
    public void updateStatisticsOnTrackDeletion(Track t, Measurements m) {
        UserStatisticFilter userFilter = new UserStatisticFilter(t.getUser());
        MongoUserStatisticKey userKey = key(userFilter);
        MongoUserStatistic v = this.dao.get(userKey);
        if (v != null) {
            this.scheduler
                    .updateUserStatisticOnTrackDeletion(userFilter, userKey, calculateOnTrackDeletionFunction, true, t, m);
        }
    }

    @Override
    public void updateStatisticsOnNewTrack(Track t) {
        UserStatisticFilter userFilter = new UserStatisticFilter(t.getUser());
        MongoUserStatisticKey userKey = key(userFilter);
        MongoUserStatistic v = this.dao.get(userKey);
        if (v != null) {
            this.scheduler.updateUserStatisticOnNewTrack(userFilter, userKey, calculateOnNewTrackFunction, true, t);
        }
    }
}
