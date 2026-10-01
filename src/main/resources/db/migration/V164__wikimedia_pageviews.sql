-- V164: monthly user pageviews of the genre articles bank question 9.1 reads (Wikimedia Pageviews, CC0 1.0).
CREATE TABLE wikimedia_pageviews_month (
    id UUID PRIMARY KEY,
    project VARCHAR(32) NOT NULL,          -- e.g. fr.wikipedia
    article VARCHAR(255) NOT NULL,         -- canonical title, underscores
    view_month DATE NOT NULL,              -- first day of the month
    views BIGINT NOT NULL,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_wikimedia_pageviews_month UNIQUE (project, article, view_month),
    CONSTRAINT ck_wikimedia_pageviews_month_views CHECK (views >= 0)
);
