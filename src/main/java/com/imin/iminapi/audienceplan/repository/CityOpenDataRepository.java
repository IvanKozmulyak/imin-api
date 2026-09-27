package com.imin.iminapi.audienceplan.repository;

import com.imin.iminapi.audienceplan.model.CityOpenData;
import org.springframework.data.repository.Repository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface CityOpenDataRepository extends Repository<CityOpenData, UUID> {

    CityOpenData save(CityOpenData row);

    Optional<CityOpenData> findByCityKeyAndDataset(String cityKey, String dataset);

    List<CityOpenData> findAll();
}
