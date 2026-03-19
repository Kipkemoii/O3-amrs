# Odoo image with Ozone configs and addons (from ozone-his/ozone).
# Build context must include: binaries/odoo/addons/, config/odoo/initializer_config/, config/odoo/config/odoo.conf
FROM mekomsolutions/odoo

ADD binaries/odoo/addons /mnt/extra-addons
ADD config/odoo/initializer_config /mnt/odoo_config
ADD config/odoo/config/odoo.conf /etc/properties/odoo.conf

# Patch core purchase_stock route company_id to avoid Odoo 16 multi-company crash
# during module initialization when imported products are company-specific.
ADD binaries/odoo/patches/purchase_stock_data.xml /opt/odoo/addons/purchase_stock/data/purchase_stock_data.xml
