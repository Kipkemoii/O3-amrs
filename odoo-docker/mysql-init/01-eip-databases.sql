-- Runs once on first MariaDB data volume init (docker-entrypoint-initdb.d).
-- Align passwords with EIP_DB_PASSWORD_ODOO / EIP_CUSTOM_ORDERS_DB_PASSWORD in docker-compose-odoo.yml.
-- For existing volumes, create DBs/users manually (see .env.odoo.example).

CREATE DATABASE IF NOT EXISTS openmrs_eip_mgt_odoo;
CREATE USER IF NOT EXISTS 'openmrs_eip_mgt_odoo'@'%' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON openmrs_eip_mgt_odoo.* TO 'openmrs_eip_mgt_odoo'@'%';

CREATE DATABASE IF NOT EXISTS openmrs_eip_mgt_custom_orders;
CREATE USER IF NOT EXISTS 'openmrs_eip_mgt_custom_orders'@'%' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON openmrs_eip_mgt_custom_orders.* TO 'openmrs_eip_mgt_custom_orders'@'%';

FLUSH PRIVILEGES;
