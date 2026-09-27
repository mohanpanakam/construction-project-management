# Sample Agreement Templates

Two ready-to-upload sale agreement templates, demonstrating every placeholder the
backend auto-fills when a draft agreement is generated (see
`backend/src/main/kotlin/com/panakam/construction/backend/routes/AgreementRoutes.kt`):

- `individual_agreement_template.txt` — single purchaser.
- `joint_agreement_template.txt` — two purchasers (e.g. spouse/co-applicant), uses the
  `{{CO_APPLICANT_*}}` placeholders which are only populated when **more than one KYC
  document** is linked to the agreement at creation time (`kycDocumentIds` has 2+ entries).

These ship as `.txt` for readability in this repo, but you can just as easily author a
template as a **Word `.docx` file** with the same `{{PLACEHOLDER}}` markers and upload
that instead — see "Supported file types" below.

## Supported file types

| Format | Upload support | Notes |
|--------|-----------------|-------|
| `.txt` | ✅ | Read as-is. |
| `.pdf` | ✅ | Plain text extracted via PDFBox. |
| `.docx` | ✅ | Plain text extracted via Apache POI (`XWPFDocument`) — paragraphs and table cells, in document order. |
| `.doc` (legacy binary Word) | ❌ | Not supported — save as `.docx` first. |

**Important limitation:** for BOTH `.pdf` and `.docx`, only the plain TEXT is extracted
— bold/italic/font/color formatting, images, and page layout are **not** preserved. The
generated agreement is always re-rendered as a simple text PDF
(`PdfGenerator.textToPdf`), so a fancily-formatted Word template will lose its styling in
the final generated document (the *text and placeholder substitution* will still be
100% correct, just plain-formatted). Keep this in mind when designing a template — plain
paragraphs with placeholders work best.

## How to use

1. In the app, go to **Project → Agreement Templates**.
2. Tap **Upload** and pick the relevant `.txt`/`.pdf`/`.docx` file from this folder (or
   tap the pencil/"Type Template" FAB and paste its contents directly).
3. When creating a draft agreement for a customer:
   - For an **individual** purchase, link only the primary buyer's KYC document.
   - For a **joint** purchase, link BOTH the primary buyer's and co-applicant's KYC
     documents (in that order — the first is treated as the primary/`{{CUSTOMER_NAME}}`,
     the second as `{{CO_APPLICANT_NAME}}`).

## Placeholder reference

| Placeholder              | Populated from                                             |
|---------------------------|-------------------------------------------------------------|
| `{{CUSTOMER_NAME}}`       | Primary KYC document holder name (falls back to customer)   |
| `{{AADHAR_NUMBER}}`       | Primary KYC document number                                  |
| `{{ADDRESS}}`             | Primary KYC document address                                 |
| `{{CUSTOMER_PHONE}}`      | Customer's phone number                                       |
| `{{CO_APPLICANT_NAME}}`   | Second linked KYC document holder name (blank if individual)  |
| `{{CO_APPLICANT_NUMBER}}` | Second linked KYC document number                             |
| `{{CO_APPLICANT_ADDRESS}}`| Second linked KYC document address                            |
| `{{PARTY_TYPE}}`          | `"Individual"` or `"Joint"` depending on # of linked KYC docs |
| `{{ALL_KYC_HOLDERS}}`     | All linked KYC holders, combined, e.g. "A (Aadhar: 123); B (PAN: XYZ)" |
| `{{PROJECT_NAME}}`        | Project name                                                  |
| `{{PROJECT_LOCATION}}`    | Project location                                              |
| `{{UNIT_NUMBER}}`         | Unit number                                                   |
| `{{FLOOR}}`               | Unit floor                                                    |
| `{{UNIT_TYPE}}`           | Unit type (e.g. 2BHK)                                         |
| `{{SBA}}`                 | Super built-up area (sq.ft)                                   |
| `{{TOTAL_AMOUNT}}`        | Total consideration, numeric (e.g. "25,00,000.00")            |
| `{{TOTAL_AMOUNT_WORDS}}`  | Same amount spelled out, e.g. "Rupees Twenty-Five Lakh Only"  |
| `{{DATE}}`                | Agreement creation date                                       |
| `{{DOCUMENT_TYPE}}`       | `"Sale Agreement"` or `"Registration"`                        |

`{{TOTAL_AMOUNT_WORDS}}` is generated server-side by
`backend/src/main/kotlin/com/panakam/construction/backend/service/NumberToWordsConverter.kt`
using the Indian numbering system (Crore/Lakh/Thousand), so every generated
agreement/registration prints the legally-conventional "Rupees ... Only" line
automatically — no manual typing of the amount in words is needed per customer.

