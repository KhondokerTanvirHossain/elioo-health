-- Web rebuild D4b (wave 2 plan): each stored image's pixel size, so the paper JSON can tell React how big a crop is
-- before it loads. Read from the JPEG's header when the object is stored; a row written before this migration has
-- none and is measured once, on its paper's first read, and never rewritten after (the UPDATE that records it only
-- touches a row whose size is still NULL). Both or neither: a size is whole and positive, or not known, never zero.
ALTER TABLE ${flyway:defaultSchema}.stored_object
    ADD COLUMN width_px integer NULL,
    ADD COLUMN height_px integer NULL;

ALTER TABLE ${flyway:defaultSchema}.stored_object
    ADD CONSTRAINT stored_object_dimensions_whole
        CHECK ((width_px IS NULL) = (height_px IS NULL) AND (width_px IS NULL OR (width_px > 0 AND height_px > 0)));

COMMENT ON COLUMN ${flyway:defaultSchema}.stored_object.width_px
    IS 'Image width in pixels from its header; NULL with height_px when not known (written before V24, or undecodable).';
COMMENT ON COLUMN ${flyway:defaultSchema}.stored_object.height_px
    IS 'Image height in pixels from its header; NULL exactly when width_px is.';
