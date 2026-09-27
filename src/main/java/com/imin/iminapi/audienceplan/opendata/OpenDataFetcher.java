package com.imin.iminapi.audienceplan.opendata;

/** Fetches one dataset for one city from its official source. */
public interface OpenDataFetcher {

    OpenDataset dataset();

    /** @throws OpenDataFetchException when the source is unreachable or its answer is incomplete */
    FetchedFigure fetch(OpenDataCity city);
}
