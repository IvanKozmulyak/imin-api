package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.repository.CityOpenDataRepository;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Keyed on (city_key, dataset) like the unique constraint; can be told to fail the next save. */
class InMemoryCityOpenDataRepository implements CityOpenDataRepository {

    final List<CityOpenData> rows = new ArrayList<>();
    int saves;
    boolean failNextSave;

    @Override
    public CityOpenData save(CityOpenData row) {
        saves++;
        if (failNextSave) {
            failNextSave = false;
            throw new DataIntegrityViolationException("uq_city_open_data_city_dataset");
        }
        if (row.getId() == null) {
            row.setId(UUID.randomUUID());
            rows.add(row);
        }
        return row;
    }

    @Override
    public Optional<CityOpenData> findByCityKeyAndDataset(String cityKey, String dataset) {
        return rows.stream().filter(r -> r.getCityKey().equals(cityKey) && r.getDataset().equals(dataset)).findFirst();
    }

    @Override
    public List<CityOpenData> findAll() {
        return List.copyOf(rows);
    }
}
