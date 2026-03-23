# -*- coding: utf-8 -*-

import csv
import logging
from pathlib import Path

from odoo import api, models

_logger = logging.getLogger(__name__)

BRANCHES_CSV = "/mnt/odoo_config/company_hierarchy/ampath.company.branches.csv"


class ResCompanyHierarchySync(models.Model):
    _inherit = "res.company"

    @api.model
    def cron_sync_company_hierarchy_from_csv(self):
        csv_path = Path(BRANCHES_CSV)
        if not csv_path.exists():
            _logger.info("AMPATH Billing: branches CSV not found at %s", BRANCHES_CSV)
            return

        with csv_path.open("r", encoding="utf-8", newline="") as file_obj:
            for row in csv.DictReader(file_obj):
                company_xml_id = (row.get("id") or "").strip()
                company_name = (row.get("name") or "").strip()
                parent_xml_id = (row.get("parent_id/id") or "").strip()
                partner_xml_id = (row.get("partner_id/id") or "").strip()
                currency_xml_id = (row.get("currency_id/id") or "base.KES").strip()
                if not company_xml_id or not company_name or not parent_xml_id or not partner_xml_id:
                    continue

                child = self.env.ref(company_xml_id, raise_if_not_found=False)
                parent = self.env.ref(parent_xml_id, raise_if_not_found=False)
                partner = self.env.ref(partner_xml_id, raise_if_not_found=False)
                currency = self.env.ref(currency_xml_id, raise_if_not_found=False)

                if child:
                    # Parent hierarchy cannot be changed after create in Odoo; skip existing rows.
                    continue

                if not parent or not partner or not currency:
                    continue
                if parent._name != "res.company" or partner._name != "res.partner" or currency._name != "res.currency":
                    continue

                company = self.create(
                    {
                        "name": company_name,
                        "parent_id": parent.id,
                        "partner_id": partner.id,
                        "currency_id": currency.id,
                    }
                )

                module, xml_name = company_xml_id.split(".", 1)
                self.env["ir.model.data"].create(
                    {
                        "module": module,
                        "name": xml_name,
                        "model": "res.company",
                        "res_id": company.id,
                    }
                )
                _logger.info("AMPATH Billing: created branch %s under %s", company_xml_id, parent_xml_id)
