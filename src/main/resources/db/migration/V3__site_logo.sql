ALTER TABLE site_images DROP CONSTRAINT IF EXISTS site_images_slot_allowed;
ALTER TABLE site_images DROP CONSTRAINT IF EXISTS site_images_slot_check;
ALTER TABLE site_images ADD CONSTRAINT site_images_slot_allowed CHECK (slot IN ('HERO','ABOUT','GALLERY','LOGO'));
