-- Web rebuild D4b (wave 2 plan): each stored image's pixel size, so the paper JSON can tell React how big a crop is
-- before it loads. Read from the JPEG's header when the object is stored. A crop row written before this migration has
-- none and dimensions_checked_at NULL: it is measured on its paper's first JSON read, and tried once only. Whatever
-- that read finds (a size, or an object that is missing, does not decode or is over the upload cap) sets
-- dimensions_checked_at, and the UPDATE that records it only touches a row whose dimensions_checked_at is still NULL,
-- so a stored size is never rewritten and an unreadable object is never fetched again. Page rows are not measured on
-- read (nothing shows a page's size yet). Both or neither: a size is whole and positive, or not known, never zero.
ALTER TABLE ${flyway:defaultSchema}.stored_object
    ADD COLUMN width_px integer NULL,
    ADD COLUMN height_px integer NULL,
    ADD COLUMN dimensions_checked_at timestamptz NULL;

ALTER TABLE ${flyway:defaultSchema}.stored_object
    ADD CONSTRAINT stored_object_dimensions_whole
        CHECK ((width_px IS NULL) = (height_px IS NULL) AND (width_px IS NULL OR (width_px > 0 AND height_px > 0)));

-- A size is only ever recorded by the read that produced it, so a sized row is never a row "never tried".
ALTER TABLE ${flyway:defaultSchema}.stored_object
    ADD CONSTRAINT stored_object_dimensions_checked
        CHECK (width_px IS NULL OR dimensions_checked_at IS NOT NULL);

COMMENT ON COLUMN ${flyway:defaultSchema}.stored_object.width_px
    IS 'Image width in pixels from its header; NULL with height_px when not known (written before the column, or unreadable).';
COMMENT ON COLUMN ${flyway:defaultSchema}.stored_object.height_px
    IS 'Image height in pixels from its header; NULL exactly when width_px is.';
COMMENT ON COLUMN ${flyway:defaultSchema}.stored_object.dimensions_checked_at
    IS 'When the size was read or found unreadable; NULL only for a row never tried, the one row a paper read measures.';
