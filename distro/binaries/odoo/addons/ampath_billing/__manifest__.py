{
    'name': 'AMPATH Billing',
    'version': '1.0',
    'summary': 'Billing module for AMPATH',
    'category': 'Healthcare/Accounting',
    'author': 'AMPATH',
    'depends': ['sale', 'account'],
    'data': [
        'security/ir.model.access.csv',
        'views/sale_order_views.xml',
        'views/account_move_views.xml',
    ],
    'installable': True,
    'application': True,
    'license': 'LGPL-3',
}