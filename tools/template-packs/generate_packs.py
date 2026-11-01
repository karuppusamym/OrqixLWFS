"""Generate the regional regulated template packs (docs/regional-template-packs.md).

The generated files in examples/pack-*.json are the source the backend packages as built-in templates; this script
only keeps the twenty variants consistent with each other. Edit here, then regenerate:

    python tools/template-packs/generate_packs.py            # write examples/pack-*.json
    python tools/template-packs/generate_packs.py --check    # exit 1 when a file differs from what would be written

Only constructs the compiler already supports are used: decisions, branches, approvals with a working calendar,
parallel and inclusive splits, several approvers, lists of rows (count and sum) and in-app notifications.
No connections and no child workflows, so every template publishes in an empty workspace once its groups exist.

Every template is illustrative: thresholds, intervals, holiday lists and document names are placeholders that a
compliance function must confirm before real use.
"""
import argparse
import datetime as dt
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "examples"

# --------------------------------------------------------------------------------------------------------------------
# Regions
# --------------------------------------------------------------------------------------------------------------------

REGIONS = {
    "in": {"name": "India", "tag": "india", "lang": "hi", "langName": "Hindi", "currency": "INR", "zone": "Asia/Kolkata"},
    "eu": {"name": "European Union", "tag": "eu", "lang": "de", "langName": "German", "currency": "EUR", "zone": "Europe/Berlin"},
    "ke": {"name": "Kenya", "tag": "kenya", "lang": "sw", "langName": "Swahili", "currency": "KES", "zone": "Africa/Nairobi"},
    "ng": {"name": "Nigeria", "tag": "nigeria", "lang": None, "langName": None, "currency": "NGN", "zone": "Africa/Lagos"},
}

PACKS = {
    "banking-mfi": "Banking and microfinance",
    "shared-services": "Shared services",
    "public-sector": "Public sector",
}

YEARS = (2026, 2027)


def easter(year):
    """Gregorian Easter Sunday (anonymous algorithm)."""
    a, b, c = year % 19, year // 100, year % 100
    d, e = b // 4, b % 4
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = c // 4, c % 4
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    month = (h + l - 7 * m + 114) // 31
    day = ((h + l - 7 * m + 114) % 31) + 1
    return dt.date(year, month, day)


def nth_saturdays(year, which):
    days = []
    for month in range(1, 13):
        first = dt.date(year, month, 1)
        offset = (5 - first.weekday()) % 7
        for n in which:
            days.append(first + dt.timedelta(days=offset + 7 * (n - 1)))
    return days


def sunday_to_monday(days):
    """Kenya and Nigeria move a holiday that falls on a Sunday to the following Monday."""
    out = set()
    for d in days:
        out.add(d)
        if d.weekday() == 6:
            out.add(d + dt.timedelta(days=1))
    return out


def holidays(region, sector):
    """Public holiday sets. Lunar-calendar dates are given for 2026 only and must be confirmed against the official
    notification; fixed and Easter-based dates cover 2026 and 2027."""
    days = set()
    for y in YEARS:
        e = easter(y)
        if region == "in":
            days |= {dt.date(y, 1, 26), dt.date(y, 8, 15), dt.date(y, 10, 2), dt.date(y, 12, 25), e - dt.timedelta(days=2)}
            if sector == "bank":
                # Banks close on the second and fourth Saturday (RBI); Mon-Sat is the working week.
                days |= set(nth_saturdays(y, (2, 4)))
        elif region == "eu":
            if sector == "bank":
                # TARGET2 closing days: the same in every euro-area country.
                days |= {dt.date(y, 1, 1), e - dt.timedelta(days=2), e + dt.timedelta(days=1), dt.date(y, 5, 1),
                         dt.date(y, 12, 25), dt.date(y, 12, 26)}
            else:
                # German nationwide public holidays (state holidays are not included).
                days |= {dt.date(y, 1, 1), e - dt.timedelta(days=2), e + dt.timedelta(days=1), dt.date(y, 5, 1),
                         e + dt.timedelta(days=39), e + dt.timedelta(days=50), dt.date(y, 10, 3), dt.date(y, 12, 25),
                         dt.date(y, 12, 26)}
        elif region == "ke":
            days |= sunday_to_monday({dt.date(y, 1, 1), e - dt.timedelta(days=2), e + dt.timedelta(days=1),
                                      dt.date(y, 5, 1), dt.date(y, 6, 1), dt.date(y, 10, 10), dt.date(y, 10, 20),
                                      dt.date(y, 12, 12), dt.date(y, 12, 25), dt.date(y, 12, 26)})
        elif region == "ng":
            days |= sunday_to_monday({dt.date(y, 1, 1), e - dt.timedelta(days=2), e + dt.timedelta(days=1),
                                      dt.date(y, 5, 1), dt.date(y, 6, 12), dt.date(y, 10, 1), dt.date(y, 12, 25),
                                      dt.date(y, 12, 26)})
    lunar_2026 = {
        # Central Government gazetted list (illustrative): Holi, Id-ul-Fitr, Ram Navami, Mahavir Jayanti, Buddha
        # Purnima, Id-ul-Zuha, Muharram, Milad-un-Nabi, Janmashtami, Dussehra, Diwali, Guru Nanak Jayanti.
        "in": ["2026-03-04", "2026-03-21", "2026-03-26", "2026-03-31", "2026-05-01", "2026-05-27", "2026-06-26",
               "2026-08-26", "2026-09-04", "2026-10-20", "2026-11-08", "2026-11-24"],
        "eu": [],
        "ke": ["2026-03-20", "2026-05-27"],  # Idd-ul-Fitr; Idd-ul-Adha (gazetted each year)
        "ng": ["2026-03-20", "2026-03-23", "2026-05-27", "2026-05-28", "2026-08-26"],  # Eid-el-Fitr, Eid-el-Kabir, Maulud
    }[region]
    days |= {dt.date.fromisoformat(d) for d in lunar_2026}
    return sorted(d.isoformat() for d in days)


def calendar(region, sector):
    """Working calendar for approval due dates. sector: bank (branch hours) or office (public/back office)."""
    weekdays = [1, 2, 3, 4, 5]
    if region == "in":
        weekdays = [1, 2, 3, 4, 5, 6] if sector == "bank" else [1, 2, 3, 4, 5]
        intervals = [("10:00", "17:00")] if sector == "bank" else [("09:30", "13:30"), ("14:00", "18:00")]
    elif region == "eu":
        intervals = [("09:00", "17:00")] if sector == "bank" else [("08:00", "12:00"), ("13:00", "16:00")]
    elif region == "ke":
        intervals = [("08:30", "16:30")] if sector == "bank" else [("08:00", "13:00"), ("14:00", "17:00")]
    else:
        intervals = [("08:00", "16:00")]
    cal = {"zone": REGIONS[region]["zone"], "weekdays": weekdays, "holidays": holidays(region, sector),
           "start": intervals[0][0], "end": intervals[-1][1],
           "revision": f"{region.upper()}-{sector}-2026-illustrative", "validFrom": "2026-01-01"}
    if len(intervals) > 1:
        cal["intervals"] = [{"start": s, "end": e} for s, e in intervals]
    return cal


# --------------------------------------------------------------------------------------------------------------------
# Second-language labels. Field titles, section titles, step labels and notifications are shown bilingually as
# "English / local". Option values, outcomes and canvas labels stay English: options are stored data, and outcome
# labels accept ASCII only. Translations are drafts for native-speaker review.
# --------------------------------------------------------------------------------------------------------------------

TR = {
    "hi": {
        # loan exception
        "Loan reference": "ऋण संदर्भ",
        "Borrower segment": "उधारकर्ता श्रेणी",
        "Loan amount (INR)": "ऋण राशि (₹)",
        "Policy exception": "नीति अपवाद",
        "What breaks policy, and why accept it": "कौन सी नीति टूटती है, और क्यों स्वीकारें",
        "Mitigants": "जोखिम कम करने के उपाय",
        "Borrower is an insider or related party": "उधारकर्ता अंदरूनी व्यक्ति या संबंधित पक्ष है",
        "Loan": "ऋण",
        "Exception": "अपवाद",
        "Branch manager review": "शाखा प्रबंधक समीक्षा",
        "Related-party compliance review": "संबंधित पक्ष अनुपालन समीक्षा",
        "Credit head approval": "क्रेडिट प्रमुख अनुमोदन",
        "Credit committee approval": "क्रेडिट समिति अनुमोदन",
        "Submit exception": "अपवाद जमा करें",
        "The loan exception was approved.": "ऋण अपवाद स्वीकृत हुआ।",
        "The loan exception was rejected. Open the request to see the reason.": "ऋण अपवाद अस्वीकृत हुआ। कारण देखने के लिए अनुरोध खोलें।",
        # kyc refresh
        "Customer name": "ग्राहक का नाम",
        "Customer type": "ग्राहक प्रकार",
        "Risk rating": "जोखिम श्रेणी",
        "KYC details changed since the last update": "पिछले अद्यतन के बाद KYC विवरण बदले हैं",
        "Officially valid document": "आधिकारिक रूप से मान्य दस्तावेज़",
        "Last KYC update": "पिछला KYC अद्यतन",
        "Customer": "ग्राहक",
        "Refresh": "पुनः KYC",
        "Re-review the customer": "ग्राहक की पुनः समीक्षा",
        "Enhanced due diligence": "उन्नत सम्यक तत्परता",
        "Head of compliance approval": "अनुपालन प्रमुख अनुमोदन",
        "Enhanced due diligence is complete.": "उन्नत सम्यक तत्परता पूरी हुई।",
        "Submit KYC refresh": "पुनः KYC जमा करें",
        "Self-declaration accepted: no change in KYC details.": "स्व-घोषणा स्वीकार: KYC विवरण में कोई परिवर्तन नहीं।",
        "KYC refreshed.": "KYC अद्यतन हुआ।",
        "KYC refresh not confirmed; account restricted. Open the request to see the reason.": "पुनः KYC की पुष्टि नहीं हुई; खाता प्रतिबंधित। कारण देखने के लिए अनुरोध खोलें।",
        # group loan
        "Group name": "समूह का नाम",
        "Group type": "समूह प्रकार",
        "Members": "सदस्य",
        "Member name": "सदस्य का नाम",
        "Member reference": "सदस्य संदर्भ",
        "Amount (INR)": "राशि (₹)",
        "Loan purpose": "ऋण का उद्देश्य",
        "Compulsory group training completed": "अनिवार्य समूह प्रशिक्षण पूरा",
        "Group meeting date": "समूह बैठक की तिथि",
        "Group": "समूह",
        "Field officer verification": "क्षेत्र अधिकारी सत्यापन",
        "Submit group loan": "समूह ऋण जमा करें",
        "The group does not meet the eligibility rules (size or training).": "समूह पात्रता नियम (आकार या प्रशिक्षण) पूरे नहीं करता।",
        "The group loan was approved and is ready for disbursement.": "समूह ऋण स्वीकृत हुआ और वितरण के लिए तैयार है।",
        "The group loan was rejected. Open the request to see the reason.": "समूह ऋण अस्वीकृत हुआ। कारण देखने के लिए अनुरोध खोलें।",
        # vendor onboarding
        "Vendor legal name": "विक्रेता का कानूनी नाम",
        "GSTIN": "जीएसटीआईएन",
        "Category": "श्रेणी",
        "Expected annual spend (INR)": "अनुमानित वार्षिक व्यय (₹)",
        "MSME (Udyam) registered": "एमएसएमई (उद्यम) पंजीकृत",
        "Vendor will process personal data": "विक्रेता व्यक्तिगत डेटा संसाधित करेगा",
        "Possible conflict of interest declared": "संभावित हितों का टकराव घोषित",
        "Vendor": "विक्रेता",
        "Risk": "जोखिम",
        "Procurement due diligence": "क्रय सम्यक तत्परता",
        "Verify bank details": "बैंक विवरण सत्यापित करें",
        "Privacy review": "गोपनीयता समीक्षा",
        "Compliance approval": "अनुपालन अनुमोदन",
        "Submit vendor": "विक्रेता जमा करें",
        "The vendor was onboarded.": "विक्रेता जोड़ा गया।",
        "The vendor was rejected. Open the request to see the reason.": "विक्रेता अस्वीकृत हुआ। कारण देखने के लिए अनुरोध खोलें।",
        # permit (building plan)
        "Applicant name": "आवेदक का नाम",
        "Property ID or survey number": "संपत्ति आईडी या सर्वे संख्या",
        "Ward or zone": "वार्ड या ज़ोन",
        "Building use": "भवन उपयोग",
        "Plot area (m²)": "भूखंड क्षेत्र (वर्ग मीटर)",
        "Floors": "मंज़िलें",
        "Building height (m)": "भवन की ऊंचाई (मीटर)",
        "Architect registration (CoA)": "वास्तुकार पंजीकरण (CoA)",
        "Applicant": "आवेदक",
        "Building": "भवन",
        "Completeness check": "पूर्णता जांच",
        "Planning scrutiny": "योजना जांच",
        "Fire safety NOC": "अग्नि सुरक्षा एनओसी",
        "Heritage review": "विरासत समीक्षा",
        "Inside a notified heritage zone": "अधिसूचित विरासत क्षेत्र के भीतर",
        "No specialist review was needed; the application goes to the decision.": "किसी विशेषज्ञ समीक्षा की आवश्यकता नहीं; आवेदन निर्णय के लिए जाता है।",
        "Permit decision": "परमिट निर्णय",
        "Commissioner decision": "आयुक्त निर्णय",
        "Submit application": "आवेदन जमा करें",
        "Your building plan was sanctioned.": "आपकी भवन योजना स्वीकृत हुई।",
        "Your application was refused. Open the request to see the reason.": "आपका आवेदन अस्वीकृत हुआ। कारण देखने के लिए अनुरोध खोलें।",
        # grant
        "Organisation name": "संगठन का नाम",
        "Organisation type": "संगठन प्रकार",
        "NGO Darpan ID": "एनजीओ दर्पण आईडी",
        "Programme": "कार्यक्रम",
        "Requested amount (INR)": "अनुरोधित राशि (₹)",
        "Own contribution (%)": "स्वयं का योगदान (%)",
        "Project summary": "परियोजना सारांश",
        "Budget lines": "बजट मदें",
        "Budget item": "बजट मद",
        "Organisation": "संगठन",
        "Project": "परियोजना",
        "Technical review panel": "तकनीकी समीक्षा पैनल",
        "Finance check": "वित्त जांच",
        "Grants committee approval": "अनुदान समिति अनुमोदन",
        "The application does not meet the programme's eligibility rules.": "आवेदन कार्यक्रम के पात्रता नियम पूरे नहीं करता।",
        "The grant was awarded.": "अनुदान स्वीकृत हुआ।",
        "The grant application was rejected. Open the request to see the reason.": "अनुदान आवेदन अस्वीकृत हुआ। कारण देखने के लिए अनुरोध खोलें।",
    },
    "de": {
        "Loan reference": "Kreditreferenz",
        "Borrower segment": "Kundensegment",
        "Loan amount (EUR)": "Kreditbetrag (EUR)",
        "Policy exception": "Richtlinienabweichung",
        "What breaks policy, and why accept it": "Welche Abweichung, und warum vertretbar",
        "Mitigants": "Risikomindernde Faktoren",
        "Borrower is an insider or related party": "Kreditnehmer ist Insider oder nahestehende Person",
        "Loan": "Kredit",
        "Exception": "Abweichung",
        "Branch manager review": "Prüfung Filialleitung",
        "Related-party compliance review": "Compliance-Prüfung nahestehende Person",
        "Credit head approval": "Genehmigung Leitung Kredit",
        "Credit committee approval": "Genehmigung Kreditausschuss",
        "Submit exception": "Abweichung einreichen",
        "The loan exception was approved.": "Die Kreditabweichung wurde genehmigt.",
        "The loan exception was rejected. Open the request to see the reason.": "Die Kreditabweichung wurde abgelehnt. Den Grund finden Sie im Antrag.",
        "Customer name": "Kundenname",
        "Customer type": "Kundenart",
        "Risk rating": "Risikoeinstufung",
        "KYC details changed since the last update": "KYC-Daten seit der letzten Aktualisierung geändert",
        "Identity document": "Ausweisdokument",
        "Last KYC update": "Letzte KYC-Aktualisierung",
        "Customer": "Kunde",
        "Refresh": "Aktualisierung",
        "Re-review the customer": "Kunden erneut prüfen",
        "Enhanced due diligence": "Verstärkte Sorgfaltspflichten",
        "Head of compliance approval": "Genehmigung Leitung Compliance",
        "Enhanced due diligence is complete.": "Verstärkte Sorgfaltsprüfung abgeschlossen.",
        "Submit KYC refresh": "KYC-Aktualisierung einreichen",
        "Self-declaration accepted: no change in KYC details.": "Selbstauskunft akzeptiert: keine Änderung der KYC-Daten.",
        "KYC refreshed.": "KYC aktualisiert.",
        "KYC refresh not confirmed; account restricted. Open the request to see the reason.": "KYC-Aktualisierung nicht bestätigt; Konto eingeschränkt. Den Grund finden Sie im Antrag.",
        "Vendor legal name": "Rechtlicher Name des Lieferanten",
        "VAT identification number": "Umsatzsteuer-Identifikationsnummer",
        "Category": "Kategorie",
        "Expected annual spend (EUR)": "Erwartetes Jahresvolumen (EUR)",
        "Vendor will process personal data": "Lieferant verarbeitet personenbezogene Daten",
        "Possible conflict of interest declared": "Möglicher Interessenkonflikt angegeben",
        "Vendor": "Lieferant",
        "Risk": "Risiko",
        "Procurement due diligence": "Einkaufsprüfung",
        "Verify bank details": "Bankverbindung prüfen",
        "Privacy review": "Datenschutzprüfung",
        "Compliance approval": "Compliance-Genehmigung",
        "Submit vendor": "Lieferant einreichen",
        "The vendor was onboarded.": "Der Lieferant wurde aufgenommen.",
        "The vendor was rejected. Open the request to see the reason.": "Der Lieferant wurde abgelehnt. Den Grund finden Sie im Antrag.",
        "Applicant name": "Name des Bauherrn",
        "Parcel (Flurstück)": "Flurstück",
        "Municipality district": "Gemarkung",
        "Building use": "Nutzung",
        "Gross floor area (m²)": "Bruttogrundfläche (m²)",
        "Floors": "Geschosse",
        "Building height (m)": "Gebäudehöhe (m)",
        "Listed building or heritage area": "Denkmal oder Denkmalbereich",
        "Applicant": "Bauherr",
        "Building": "Gebäude",
        "Completeness check": "Vollständigkeitsprüfung",
        "Planning scrutiny": "Bauplanungsrechtliche Prüfung",
        "Fire safety review": "Brandschutzprüfung",
        "Heritage review": "Denkmalschutzprüfung",
        "No specialist review was needed; the application goes to the decision.": "Keine Fachprüfung erforderlich; der Antrag geht zur Entscheidung.",
        "Permit decision": "Entscheidung Bauaufsicht",
        "Head of department decision": "Entscheidung Amtsleitung",
        "Submit application": "Antrag einreichen",
        "Your building permit was granted.": "Ihre Baugenehmigung wurde erteilt.",
        "Your application was refused. Open the request to see the reason.": "Ihr Antrag wurde abgelehnt. Den Grund finden Sie im Antrag.",
        "Organisation name": "Name der Organisation",
        "Organisation type": "Organisationsform",
        "Register number": "Registernummer",
        "Programme": "Förderprogramm",
        "Requested amount (EUR)": "Beantragter Betrag (EUR)",
        "Own contribution (%)": "Eigenanteil (%)",
        "Project summary": "Projektbeschreibung",
        "Budget lines": "Kostenplan",
        "Budget item": "Kostenposition",
        "Over the de minimis ceiling (3 years)": "Über der De-minimis-Höchstgrenze (3 Jahre)",
        "Organisation": "Organisation",
        "Project": "Projekt",
        "Technical review panel": "Fachgutachter",
        "Finance check": "Haushaltsprüfung",
        "Grants committee approval": "Genehmigung Bewilligungsausschuss",
        "The application does not meet the programme's eligibility rules.": "Der Antrag erfüllt die Fördervoraussetzungen des Programms nicht.",
        "The grant was awarded.": "Die Förderung wurde bewilligt.",
        "The grant application was rejected. Open the request to see the reason.": "Der Förderantrag wurde abgelehnt. Den Grund finden Sie im Antrag.",
    },
    "sw": {
        "Loan reference": "Kumbukumbu ya mkopo",
        "Borrower segment": "Aina ya mkopaji",
        "Loan amount (KES)": "Kiasi cha mkopo (KES)",
        "Policy exception": "Upekee wa sera",
        "What breaks policy, and why accept it": "Sera ipi inavunjwa, na kwa nini ikubaliwe",
        "Mitigants": "Hatua za kupunguza hatari",
        "Borrower is an insider or related party": "Mkopaji ni mhusika wa ndani au wa karibu",
        "Loan": "Mkopo",
        "Exception": "Upekee",
        "Branch manager review": "Ukaguzi wa meneja wa tawi",
        "Related-party compliance review": "Ukaguzi wa uzingatiaji wa mhusika wa karibu",
        "Credit head approval": "Idhini ya mkuu wa mikopo",
        "Credit committee approval": "Idhini ya kamati ya mikopo",
        "Submit exception": "Wasilisha upekee",
        "The loan exception was approved.": "Upekee wa mkopo umeidhinishwa.",
        "The loan exception was rejected. Open the request to see the reason.": "Upekee wa mkopo umekataliwa. Fungua ombi kuona sababu.",
        "Customer name": "Jina la mteja",
        "Customer type": "Aina ya mteja",
        "Risk rating": "Kiwango cha hatari",
        "KYC details changed since the last update": "Taarifa za KYC zimebadilika tangu sasisho la mwisho",
        "Identity document": "Hati ya utambulisho",
        "Last KYC update": "Sasisho la mwisho la KYC",
        "Customer": "Mteja",
        "Refresh": "Sasisho",
        "Re-review the customer": "Kagua mteja upya",
        "Enhanced due diligence": "Uchunguzi wa kina",
        "Head of compliance approval": "Idhini ya mkuu wa uzingatiaji",
        "Enhanced due diligence is complete.": "Uchunguzi wa kina umekamilika.",
        "Submit KYC refresh": "Wasilisha sasisho la KYC",
        "Self-declaration accepted: no change in KYC details.": "Tamko binafsi limekubaliwa: hakuna mabadiliko ya taarifa za KYC.",
        "KYC refreshed.": "KYC imesasishwa.",
        "KYC refresh not confirmed; account restricted. Open the request to see the reason.": "Sasisho la KYC halijathibitishwa; akaunti imezuiwa. Fungua ombi kuona sababu.",
        "Group name": "Jina la kikundi",
        "Group type": "Aina ya kikundi",
        "Members": "Wanachama",
        "Member name": "Jina la mwanachama",
        "Member number": "Nambari ya mwanachama",
        "Amount (KES)": "Kiasi (KES)",
        "Loan purpose": "Madhumuni ya mkopo",
        "Group registered with Social Development": "Kikundi kimesajiliwa na Maendeleo ya Jamii",
        "Group meeting date": "Tarehe ya mkutano wa kikundi",
        "Group": "Kikundi",
        "Field officer verification": "Uthibitisho wa afisa wa nyanjani",
        "Submit group loan": "Wasilisha mkopo wa kikundi",
        "The group does not meet the eligibility rules (size or registration).": "Kikundi hakitimizi masharti ya ustahiki (idadi au usajili).",
        "The group loan was approved and is ready for disbursement.": "Mkopo wa kikundi umeidhinishwa na uko tayari kutolewa.",
        "The group loan was rejected. Open the request to see the reason.": "Mkopo wa kikundi umekataliwa. Fungua ombi kuona sababu.",
        "Vendor legal name": "Jina rasmi la msambazaji",
        "KRA PIN": "PIN ya KRA",
        "Category": "Kundi",
        "Expected annual spend (KES)": "Matumizi yanayotarajiwa kwa mwaka (KES)",
        "AGPO certificate holder": "Mwenye cheti cha AGPO",
        "Vendor will process personal data": "Msambazaji atachakata data binafsi",
        "Possible conflict of interest declared": "Mgongano wa maslahi umetangazwa",
        "Vendor": "Msambazaji",
        "Risk": "Hatari",
        "Procurement due diligence": "Uchunguzi wa ununuzi",
        "Verify bank details": "Thibitisha taarifa za benki",
        "Privacy review": "Ukaguzi wa faragha",
        "Compliance approval": "Idhini ya uzingatiaji",
        "Submit vendor": "Wasilisha msambazaji",
        "The vendor was onboarded.": "Msambazaji amesajiliwa.",
        "The vendor was rejected. Open the request to see the reason.": "Msambazaji amekataliwa. Fungua ombi kuona sababu.",
        "Business name": "Jina la biashara",
        "Business activity": "Shughuli ya biashara",
        "Premises area (m²)": "Ukubwa wa eneo la biashara (m²)",
        "Employees": "Wafanyakazi",
        "Ward": "Wadi",
        "Business": "Biashara",
        "Premises": "Eneo la biashara",
        "Completeness check": "Ukaguzi wa ukamilifu",
        "Public health inspection": "Ukaguzi wa afya ya umma",
        "Fire safety inspection": "Ukaguzi wa usalama wa moto",
        "No inspection was needed; the application goes to the decision.": "Hakuna ukaguzi uliohitajika; ombi linaenda kwa uamuzi.",
        "Permit decision": "Uamuzi wa kibali",
        "Chief officer decision": "Uamuzi wa afisa mkuu",
        "Submit application": "Wasilisha ombi",
        "Your single business permit was issued.": "Kibali chako cha biashara kimetolewa.",
        "Your application was refused. Open the request to see the reason.": "Ombi lako limekataliwa. Fungua ombi kuona sababu.",
        "Organisation name": "Jina la shirika",
        "Organisation type": "Aina ya shirika",
        "Registration number": "Nambari ya usajili",
        "Programme": "Mpango",
        "Requested amount (KES)": "Kiasi kinachoombwa (KES)",
        "Own contribution (%)": "Mchango binafsi (%)",
        "Project summary": "Muhtasari wa mradi",
        "Budget lines": "Vipengele vya bajeti",
        "Budget item": "Kipengele cha bajeti",
        "Organisation": "Shirika",
        "Project": "Mradi",
        "Technical review panel": "Jopo la ukaguzi wa kiufundi",
        "Finance check": "Ukaguzi wa fedha",
        "Grants committee approval": "Idhini ya kamati ya ruzuku",
        "The application does not meet the programme's eligibility rules.": "Ombi halitimizi masharti ya ustahiki ya mpango.",
        "The grant was awarded.": "Ruzuku imetolewa.",
        "The grant application was rejected. Open the request to see the reason.": "Ombi la ruzuku limekataliwa. Fungua ombi kuona sababu.",
    },
}


class Text:
    def __init__(self, region):
        self.lang = REGIONS[region]["lang"]

    def __call__(self, en, limit=200):
        if not self.lang:
            out = en
        else:
            local = TR[self.lang].get(en)
            if local is None:
                raise KeyError(f"missing {self.lang} translation for {en!r}")
            out = f"{en} / {local}"
        if len(out) > limit:
            raise ValueError(f"label over {limit} characters: {out!r}")
        return out


# --------------------------------------------------------------------------------------------------------------------
# Builder
# --------------------------------------------------------------------------------------------------------------------

class Flow:
    def __init__(self):
        self.nodes, self.transitions = [], []

    def node(self, id, type, label, **config):
        self.nodes.append({"id": id, "type": type, "label": label, "config": config})
        return id

    def go(self, a, b, on="next", label=None, condition=None):
        t = {"id": f"t{len(self.transitions) + 1}", "from": a, "to": b, "on": on}
        if label:
            t["label"] = label
        if condition is not None:
            t["condition"] = condition
        self.transitions.append(t)

    def approval(self, id, label, group, cal=None, hours=None, **extra):
        cfg = {"candidateGroupRef": group, "rejectReasonRequired": True}
        if hours:
            cfg["dueAfterHours"] = hours
        if cal:
            cfg["calendar"] = cal
        cfg.update(extra)
        return self.node(id, "approval", label, **cfg)

    def notify(self, id, label, message):
        return self.node(id, "notification", label, channel="inApp", recipient="initiator", message=message)

    def end(self, id, label, outcome, kind):
        return self.node(id, "end", label, businessOutcome=outcome, outcomeKind=kind)

    def layout(self):
        depth = {"start": 0}
        changed = True
        while changed:
            changed = False
            for t in self.transitions:
                if t["from"] in depth and depth.get(t["to"], -1) < depth[t["from"]] + 1 and depth[t["from"]] < 40:
                    depth[t["to"]] = depth[t["from"]] + 1
                    changed = True
        rows = {}
        positions = {}
        for n in self.nodes:
            d = depth.get(n["id"], 0)
            r = rows.get(d, 0)
            rows[d] = r + 1
            positions[n["id"]] = {"x": 80 + 240 * d, "y": 80 + 160 * r}
        return {"positions": positions, "viewport": {"x": 0, "y": 0, "zoom": 0.6}}


def eq(ref, value):
    return {"fieldRef": ref, "operator": "eq", "value": value}


def string(title, lo=1, hi=200, **kw):
    return {"type": "string", "title": title, "minLength": lo, "maxLength": hi, **kw}


def money(title, lo=1, hi=10_000_000_000):
    return {"type": "number", "title": title, "minimum": lo, "maximum": hi, "multipleOf": 0.01}


def choice(title, options):
    return {"type": "string", "title": title, "enum": options}


def flag(title):
    return {"type": "boolean", "title": title}


def document(region, pack, kind, key, name, description, flow, inputs, required, ui, decisions, outputs, owner,
             mapping):
    reg = REGIONS[region]
    tags = [f"pack:{pack}", f"region:{reg['tag']}", f"template:{kind}", f"owner:{owner}", "illustrative"]
    if reg["lang"]:
        tags.append(f"lang:{reg['lang']}")
    return {
        "schemaVersion": "0.1",
        "workflowKey": key,
        "name": name,
        "metadata": {"description": description, "tags": tags},
        "inputSchema": {"type": "object", "additionalProperties": False, "required": required, "properties": inputs},
        "inputUi": ui,
        "outputSchema": {"type": "object", "additionalProperties": False, "properties": outputs},
        "triggers": ["manual", "api"],
        "nodes": flow.nodes,
        "transitions": flow.transitions,
        "decisions": decisions,
        "execution": {"maxConcurrentRuns": 200, "maxAttempts": 3, "retryDelaySeconds": 30},
        "security": {"selfApproval": "deny"},
        "dependencies": {"connections": []},
        "outputMapping": mapping,
        "uiLayout": flow.layout(),
    }


ILLUSTRATIVE = ("Illustrative regional template, not a regulatory-compliant product: thresholds, holiday lists and "
                "document names are placeholders for your compliance function to confirm.")

# --------------------------------------------------------------------------------------------------------------------
# Banking and microfinance: loan exception approval
# --------------------------------------------------------------------------------------------------------------------

LOAN_EXCEPTION = {
    "in": {"segments": ["Individual", "MSME", "Agriculture - Kisan Credit Card", "Housing"],
           "exceptions": ["Rate concession", "Tenor beyond policy", "FOIR above policy", "Collateral shortfall",
                          "Credit bureau score below cut-off", "Documentation deferral"],
           "mid": 2_500_000, "high": 10_000_000, "midText": "INR 25 lakh", "highText": "INR 1 crore",
           "context": "Deviation limits follow a delegation of financial powers; loans to directors and their relatives need board-level clearance."},
    "eu": {"segments": ["Consumer", "Residential mortgage", "SME"],
           "exceptions": ["Rate concession", "Tenor beyond policy", "DSTI above limit", "LTV above limit",
                          "Collateral shortfall", "Covenant waiver"],
           "mid": 100_000, "high": 500_000, "midText": "EUR 100,000", "highText": "EUR 500,000",
           "context": "Loans to members of the management body and related parties need the separate approval CRD Article 88 asks for."},
    "ke": {"segments": ["Individual member", "Business member", "Chama or group member"],
           "exceptions": ["Rate concession", "Tenor beyond policy", "Deposit multiple exceeded", "Guarantor shortfall",
                          "Negative CRB listing", "Collateral shortfall"],
           "mid": 1_000_000, "high": 5_000_000, "midText": "KES 1,000,000", "highText": "KES 5,000,000",
           "context": "Built for a SACCO or microfinance bank; the credit committee approves insider loans as SASRA rules expect."},
    "ng": {"segments": ["Individual", "SME", "Cooperative member"],
           "exceptions": ["Rate concession", "Tenor beyond policy", "Debt-to-income above policy",
                          "Collateral shortfall", "Credit bureau score below cut-off", "Insider-related exception"],
           "mid": 10_000_000, "high": 50_000_000, "midText": "NGN 10 million", "highText": "NGN 50 million",
           "context": "Built for a microfinance or cooperative bank; insider-related credit goes through compliance as CBN rules expect."},
}


def loan_exception(region):
    t, c, p = Text(region), LOAN_EXCEPTION[region], REGIONS[region]
    cal = calendar(region, "bank")
    cur = p["currency"]
    f = Flow()
    f.node("start", "start", "Exception submitted")
    f.node("level", "decision", "Set the approval level", decisionRef="exception-level")
    f.approval("branchReview", t("Branch manager review"), "branch-managers", cal, 16)
    f.node("relatedRoute", "branch", "Related party?")
    f.approval("compliance", t("Related-party compliance review"), "compliance-officers", cal, 24)
    f.node("levelRoute", "branch", "Which approval level?")
    f.approval("creditHead", t("Credit head approval"), "credit-heads", cal, 16, escalation={"intervalHours": 8, "maxNotices": 3})
    f.node("committee", "approval", t("Credit committee approval"), candidateGroupRef="credit-committee",
           rejectReasonRequired=True, multiInstance={"approvers": 2})
    f.notify("notifyApproved", "Tell requester it was approved", t("The loan exception was approved."))
    f.notify("notifyRejected", "Tell requester it was rejected", t("The loan exception was rejected. Open the request to see the reason."))
    f.end("approved", "Approved", "Approved", "success")
    f.end("rejected", "Rejected", "Rejected", "rejected")
    f.go("start", "level")
    f.go("level", "branchReview")
    f.go("branchReview", "relatedRoute", "approved")
    f.go("branchReview", "notifyRejected", "rejected")
    f.go("relatedRoute", "compliance", "condition", "Director, staff or related party", eq("/input/relatedParty", True))
    f.go("relatedRoute", "levelRoute", "default", "Not related")
    f.go("compliance", "levelRoute", "approved")
    f.go("compliance", "notifyRejected", "rejected")
    f.go("levelRoute", "committee", "condition", f"{c['highText']} or more", eq("/steps/level/level", "committee"))
    f.go("levelRoute", "creditHead", "condition", f"{c['midText']} or more", eq("/steps/level/level", "creditHead"))
    f.go("levelRoute", "notifyApproved", "default", "Within branch powers")
    f.go("creditHead", "notifyApproved", "approved")
    f.go("creditHead", "notifyRejected", "rejected")
    f.go("committee", "notifyApproved", "approved")
    f.go("committee", "notifyRejected", "rejected")
    f.go("notifyApproved", "approved")
    f.go("notifyRejected", "rejected")
    inputs = {
        "loanReference": string(t("Loan reference", 100), 3, 30, pattern="^[A-Za-z0-9-]{3,30}$",
                                patternMessage="Use 3 to 30 letters, digits and dashes, for example LN-2026-0042."),
        "borrowerSegment": choice(t("Borrower segment", 100), c["segments"]),
        "amount": money(t(f"Loan amount ({cur})", 100)),
        "exceptionType": choice(t("Policy exception", 100), c["exceptions"]),
        "exceptionDetail": string(t("What breaks policy, and why accept it", 100), 10, 2000),
        "mitigants": string(t("Mitigants", 100), 0, 2000),
        "relatedParty": flag(t("Borrower is an insider or related party", 100)),
    }
    order = list(inputs)
    ui = {"order": order,
          "widgets": {"borrowerSegment": "select", "amount": "number", "exceptionType": "select",
                      "exceptionDetail": "textarea", "mitigants": "textarea", "relatedParty": "checkbox"},
          "submitLabel": t("Submit exception", 80),
          "layout": {"sections": [
              {"id": "loan", "title": t("Loan"), "columns": 2, "fields": ["loanReference", "borrowerSegment", "amount", "relatedParty"]},
              {"id": "exception", "title": t("Exception"), "columns": 1, "fields": ["exceptionType", "exceptionDetail", "mitigants"]}]}}
    decisions = [{"key": "exception-level", "version": 1, "hitPolicy": "FIRST", "rows": [
        {"when": {"fieldRef": "/input/amount", "operator": "gte", "value": c["high"]}, "then": {"level": "committee"}},
        {"when": {"fieldRef": "/input/amount", "operator": "gte", "value": c["mid"]}, "then": {"level": "creditHead"}}],
        "default": {"level": "branch"}}]
    outputs = {"approvalLevel": {"type": "string", "title": "Approval level"},
               "branchReviewer": {"type": "string", "title": "Branch reviewer"}}
    mapping = {"approvalLevel": {"fieldRef": "/steps/level/level", "required": True},
               "branchReviewer": {"fieldRef": "/steps/branchReview/decidedBy", "required": False}}
    desc = (f"{p['name']} loan exception approval in {cur}. A loan that breaks credit policy goes to the branch manager, "
            f"then by amount to the credit head (from {c['midText']}) or a two-member credit committee (from {c['highText']}). "
            f"Related-party loans also need compliance. Due dates count {p['name']} banking hours and public holidays. "
            f"{c['context']} {ILLUSTRATIVE}")
    name = f"Loan exception approval ({p['name']})"
    return document(region, "banking-mfi", "loan-exception", f"loan-exception-{region}", name, desc, f, inputs,
                    ["loanReference", "borrowerSegment", "amount", "exceptionType", "exceptionDetail", "relatedParty"], ui, decisions,
                    outputs, "Credit operations", mapping)


# --------------------------------------------------------------------------------------------------------------------
# Banking and microfinance: KYC refresh (extends kyc-periodic-review: same required inputs, group and output key)
# --------------------------------------------------------------------------------------------------------------------

KYC = {
    "in": {"years": (2, 8, 10), "docTitle": "Officially valid document",
           "docs": ["Aadhaar (offline XML or masked copy)", "Passport", "Voter ID", "Driving licence", "NREGA job card", "PAN (with another OVD)"],
           "context": "Periodic updation every 2, 8 or 10 years by risk, with self-declaration when nothing changed, as the RBI KYC Master Direction describes."},
    "eu": {"years": (1, 3, 5), "docTitle": "Identity document",
           "docs": ["National identity card", "Passport", "Residence permit", "Commercial register extract"],
           "context": "Risk-based review cycle under the EU anti-money-laundering framework; the yearly, three-year and five-year cycle is a common choice, not a legal minimum."},
    "ke": {"years": (1, 3, 5), "docTitle": "Identity document",
           "docs": ["National ID", "Maisha card", "Passport", "Alien ID", "Certificate of incorporation"],
           "context": "Risk-based customer due diligence refresh as POCAMLA and CBK guidance expect; intervals are placeholders."},
    "ng": {"years": (1, 3, 5), "docTitle": "Identity document",
           "docs": ["NIN slip or card", "International passport", "Voter card", "Driver's licence", "CAC certificate"],
           "context": "Risk-based KYC refresh with BVN or NIN on file as CBN rules expect; intervals are placeholders."},
}


def kyc_refresh(region):
    t, c, p = Text(region), KYC[region], REGIONS[region]
    cal = calendar(region, "bank")
    f = Flow()
    f.node("start", "start", "Refresh due")
    f.node("interval", "decision", "Set the next refresh interval", decisionRef="refresh-interval")
    f.node("path", "decision", "Choose the review path", decisionRef="refresh-path")
    f.node("pathRoute", "branch", "Which review?")
    f.notify("notifySelf", "Tell requester the self-declaration was accepted", t("Self-declaration accepted: no change in KYC details."))
    f.approval("review", t("Re-review the customer"), "kyc-analysts", cal, 24)
    f.node("eddSplit", "parallelSplit", "Review and due diligence together", joinRef="eddJoin")
    f.approval("eddReview", t("Re-review the customer"), "kyc-analysts", cal, 24)
    f.approval("edd", t("Enhanced due diligence"), "compliance-officers", cal, 40, timeoutAfter="P5D")
    f.approval("escalation", t("Head of compliance approval"), "head-of-compliance", cal, 16)
    f.notify("eddDone", "Record that due diligence is complete", t("Enhanced due diligence is complete."))
    f.node("eddJoin", "parallelJoin", "Both finished")
    f.notify("notifyDone", "Tell requester KYC is refreshed", t("KYC refreshed."))
    f.notify("notifyRestricted", "Tell requester the account is restricted",
             t("KYC refresh not confirmed; account restricted. Open the request to see the reason."))
    f.end("refreshed", "Refreshed", "Refreshed", "success")
    f.end("restricted", "Restricted", "Restricted", "rejected")
    f.go("start", "interval")
    f.go("interval", "path")
    f.go("path", "pathRoute")
    f.go("pathRoute", "eddSplit", "condition", "High risk", eq("/steps/path/route", "edd"))
    f.go("pathRoute", "notifySelf", "condition", "Low risk, nothing changed", eq("/steps/path/route", "selfDeclaration"))
    f.go("pathRoute", "review", "default", "Medium risk or details changed")
    f.go("notifySelf", "refreshed")
    f.go("review", "notifyDone", "approved")
    f.go("review", "notifyRestricted", "rejected")
    f.go("eddSplit", "eddReview")
    f.go("eddSplit", "edd")
    f.go("eddReview", "eddJoin", "approved")
    f.go("eddReview", "notifyRestricted", "rejected")
    f.go("edd", "eddDone", "approved")
    f.go("edd", "notifyRestricted", "rejected")
    f.go("edd", "escalation", "timedOut", "Not decided in 5 days")
    f.go("escalation", "eddDone", "approved")
    f.go("eddDone", "eddJoin")
    f.go("escalation", "notifyRestricted", "rejected")
    f.go("eddJoin", "notifyDone")
    f.go("notifyDone", "refreshed")
    f.go("notifyRestricted", "restricted")
    inputs = {
        "customerName": string(t("Customer name", 100)),
        "customerType": choice(t("Customer type", 100), ["individual", "corporate"]),
        "riskRating": choice(t("Risk rating", 100), ["low", "medium", "high"]),
        "detailsChanged": flag(t("KYC details changed since the last update", 100)),
        "idDocument": choice(t(c["docTitle"], 100), c["docs"]),
        "lastUpdated": {"type": "string", "format": "date", "title": t("Last KYC update", 100)},
    }
    ui = {"order": list(inputs),
          "widgets": {"customerType": "radio", "riskRating": "radio", "detailsChanged": "checkbox", "idDocument": "select"},
          "submitLabel": t("Submit KYC refresh", 80),
          "layout": {"sections": [
              {"id": "customer", "title": t("Customer"), "columns": 2, "fields": ["customerName", "customerType", "riskRating"]},
              {"id": "refresh", "title": t("Refresh"), "columns": 2, "fields": ["detailsChanged", "idDocument", "lastUpdated"]}]}}
    hi, mid, lo = c["years"]
    decisions = [
        {"key": "refresh-interval", "version": 1, "hitPolicy": "FIRST", "rows": [
            {"when": eq("/input/riskRating", "high"), "then": {"intervalYears": hi}},
            {"when": eq("/input/riskRating", "low"), "then": {"intervalYears": lo}}],
         "default": {"intervalYears": mid}},
        {"key": "refresh-path", "version": 1, "hitPolicy": "FIRST", "rows": [
            {"when": eq("/input/riskRating", "high"), "then": {"route": "edd"}},
            {"when": {"all": [eq("/input/detailsChanged", True), {"fieldRef": "/input/riskRating", "operator": "ne", "value": "high"}]},
             "then": {"route": "review"}},
            {"when": eq("/input/riskRating", "low"), "then": {"route": "selfDeclaration"}}],
         "default": {"route": "review"}},
    ]
    outputs = {"reviewedBy": {"type": "string", "title": "Reviewed by"},
               "refreshIntervalYears": {"type": "integer", "title": "Next refresh in years"}}
    mapping = {"reviewedBy": {"fieldRef": "/steps/review/decidedBy", "required": False},
               "refreshIntervalYears": {"fieldRef": "/steps/interval/intervalYears", "required": True}}
    desc = (f"{p['name']} KYC refresh. Extends KYC periodic review (same customerName, customerType, kyc-analysts and "
            f"reviewedBy, plus risk rating and changed-details answers) with a risk-based path: low risk with no change "
            f"is accepted on self-declaration, medium risk or changed details get an analyst re-review, high risk gets re-review and enhanced due diligence together, "
            f"escalating after five days. Next refresh in {hi}, {mid} or {lo} years. {c['context']} {ILLUSTRATIVE}")
    return document(region, "banking-mfi", "kyc-refresh", f"kyc-refresh-{region}", f"KYC refresh ({p['name']})", desc, f,
                    inputs, ["customerName", "customerType", "riskRating", "detailsChanged"], ui, decisions, outputs,
                    "KYC operations", mapping)


# --------------------------------------------------------------------------------------------------------------------
# Banking and microfinance: cooperative / MFI group loan approval
# --------------------------------------------------------------------------------------------------------------------

GROUP = {
    "in": {"types": ["Joint liability group - JLG", "Self-help group - SHG"],
           "rules": [("Joint liability group - JLG", 4, 10), ("Self-help group - SHG", 10, 20)],
           "check": "Compulsory group training completed", "memberRef": "Member reference",
           "purposes": ["Income generation", "Agriculture and allied", "Small trade", "Education", "Housing repair"],
           "committee": 1_000_000, "committeeText": "INR 10 lakh",
           "context": "JLG of 4 to 10 and SHG of 10 to 20 members after compulsory group training, in line with NABARD and RBI microfinance practice."},
    "ke": {"types": ["Chama", "SACCO members group"],
           "rules": [("Chama", 5, 20), ("SACCO members group", 3, 20)],
           "check": "Group registered with Social Development", "memberRef": "Member number",
           "purposes": ["Income generation", "Agriculture", "Small trade", "School fees", "Asset purchase"],
           "committee": 2_000_000, "committeeText": "KES 2,000,000",
           "context": "Group lending for a SACCO or microfinance bank; the credit committee is the one the Sacco Societies Act requires."},
    "ng": {"types": ["Cooperative society", "Esusu or Ajo group", "Trade association"],
           "rules": [("Cooperative society", 5, 20), ("Esusu or Ajo group", 5, 20), ("Trade association", 5, 20)],
           "check": "Group registered with the cooperative department or association", "memberRef": "Member number",
           "purposes": ["Income generation", "Agriculture", "Small trade", "School fees", "Asset purchase"],
           "committee": 20_000_000, "committeeText": "NGN 20 million",
           "context": "Group lending for a microfinance bank or cooperative; member BVNs stay in the core system, not in the form."},
}


def group_loan(region):
    t, c, p = Text(region), GROUP[region], REGIONS[region]
    cal = calendar(region, "bank")
    cur = p["currency"]
    f = Flow()
    f.node("start", "start", "Group loan submitted")
    f.node("eligibility", "decision", "Check group size and registration", decisionRef="group-eligibility")
    f.node("eligibleRoute", "branch", "Eligible?")
    f.notify("notifyIneligible", "Tell requester the group is not eligible",
             t("The group does not meet the eligibility rules (size or training)." if region == "in"
               else "The group does not meet the eligibility rules (size or registration)."))
    f.approval("fieldVerification", t("Field officer verification"), "field-officers", cal, 24)
    f.node("level", "decision", "Set the approval level", decisionRef="group-level")
    f.node("levelRoute", "branch", "Committee needed?")
    f.approval("branchApproval", t("Branch manager review"), "branch-managers", cal, 16)
    f.node("committee", "approval", t("Credit committee approval"), candidateGroupRef="credit-committee",
           rejectReasonRequired=True, multiInstance={"approvers": 2})
    f.notify("notifyApproved", "Tell requester it was approved", t("The group loan was approved and is ready for disbursement."))
    f.notify("notifyRejected", "Tell requester it was rejected", t("The group loan was rejected. Open the request to see the reason."))
    f.end("approved", "Approved", "Approved", "success")
    f.end("rejected", "Rejected", "Rejected", "rejected")
    f.end("ineligible", "Ineligible", "Ineligible", "rejected")
    f.go("start", "eligibility")
    f.go("eligibility", "eligibleRoute")
    f.go("eligibleRoute", "notifyIneligible", "condition", "Size or registration rule not met",
         eq("/steps/eligibility/eligible", False))
    f.go("eligibleRoute", "fieldVerification", "default", "Eligible")
    f.go("notifyIneligible", "ineligible")
    f.go("fieldVerification", "level", "approved")
    f.go("fieldVerification", "notifyRejected", "rejected")
    f.go("level", "levelRoute")
    f.go("levelRoute", "committee", "condition", f"Total {c['committeeText']} or more", eq("/steps/level/level", "committee"))
    f.go("levelRoute", "branchApproval", "default", "Within branch powers")
    f.go("branchApproval", "notifyApproved", "approved")
    f.go("branchApproval", "notifyRejected", "rejected")
    f.go("committee", "notifyApproved", "approved")
    f.go("committee", "notifyRejected", "rejected")
    f.go("notifyApproved", "approved")
    f.go("notifyRejected", "rejected")
    max_members = max(r[2] for r in c["rules"])
    inputs = {
        "groupName": string(t("Group name", 100)),
        "groupType": choice(t("Group type", 100), c["types"]),
        "registered": flag(t(c["check"], 100)),
        "meetingDate": {"type": "string", "format": "date", "title": t("Group meeting date", 100)},
        "loanPurpose": choice(t("Loan purpose", 100), c["purposes"]),
        "members": {"type": "array", "title": t("Members"), "minItems": 1, "maxItems": max_members,
                    "items": {"type": "object", "additionalProperties": False, "required": ["memberName", "amount"],
                              "properties": {
                                  "memberName": string(t("Member name", 100)),
                                  "memberRef": string(t(c["memberRef"], 100), 0, 40),
                                  "amount": money(t(f"Amount ({cur})", 100), 1, 100_000_000)}}},
    }
    ui = {"order": list(inputs),
          "widgets": {"groupType": "select", "registered": "checkbox", "loanPurpose": "select"},
          "submitLabel": t("Submit group loan", 80),
          "layout": {"sections": [
              {"id": "group", "title": t("Group"), "columns": 2, "fields": ["groupName", "groupType", "registered", "meetingDate", "loanPurpose"]},
              {"id": "members", "title": t("Members"), "columns": 1, "fields": ["members"]}]}}
    rows = [{"when": eq("/input/registered", False), "then": {"eligible": False}}]
    for kind, lo, hi in c["rules"]:
        rows.append({"when": {"all": [eq("/input/groupType", kind),
                                      {"fieldRef": "/input/members/count", "operator": "lt", "value": lo}]},
                     "then": {"eligible": False}})
        rows.append({"when": {"all": [eq("/input/groupType", kind),
                                      {"fieldRef": "/input/members/count", "operator": "gt", "value": hi}]},
                     "then": {"eligible": False}})
    decisions = [
        {"key": "group-eligibility", "version": 1, "hitPolicy": "FIRST", "rows": rows, "default": {"eligible": True}},
        {"key": "group-level", "version": 1, "hitPolicy": "FIRST", "rows": [
            {"when": {"fieldRef": "/input/members/sum/amount", "operator": "gte", "value": c["committee"]},
             "then": {"level": "committee"}}], "default": {"level": "branch"}},
    ]
    outputs = {"memberCount": {"type": "integer", "title": "Members"},
               "totalAmount": {"type": "number", "title": f"Total amount ({cur})"},
               "verifiedBy": {"type": "string", "title": "Verified by"}}
    mapping = {"memberCount": {"fieldRef": "/input/members/count", "required": True},
               "totalAmount": {"fieldRef": "/input/members/sum/amount", "required": True},
               "verifiedBy": {"fieldRef": "/steps/fieldVerification/decidedBy", "required": False}}
    sizes = "; ".join(f"{k} {lo} to {hi} members" for k, lo, hi in c["rules"])
    desc = (f"{p['name']} cooperative and microfinance group loan in {cur}. Rules check group size ({sizes}) and "
            f"registration; a field officer verifies the group, then the branch manager approves, or a two-member credit "
            f"committee when the total is {c['committeeText']} or more. Due dates count {p['name']} banking hours and "
            f"holidays. {c['context']} {ILLUSTRATIVE}")
    return document(region, "banking-mfi", "group-loan", f"group-loan-{region}", f"Group loan approval ({p['name']})",
                    desc, f, inputs, ["groupName", "groupType", "registered", "loanPurpose", "members"], ui, decisions,
                    outputs, "Microfinance operations", mapping)


# --------------------------------------------------------------------------------------------------------------------
# Shared services: vendor onboarding
# --------------------------------------------------------------------------------------------------------------------

VENDOR = {
    "in": {"tax": "GSTIN", "pattern": "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$",
           "message": "Enter a 15-character GSTIN, for example 27AAPFU0939F1ZV.",
           "extra": ("msme", "MSME (Udyam) registered"), "high": 5_000_000, "highText": "INR 50 lakh",
           "privacy": "Digital Personal Data Protection Act 2023",
           "context": "MSME vendors are flagged so payment within 45 days (MSMED Act section 43B(h)) can be tracked."},
    "eu": {"tax": "VAT identification number", "pattern": "^[A-Z]{2}[A-Za-z0-9]{2,13}$",
           "message": "Enter a VAT number starting with the country code, for example DE123456789.",
           "extra": None, "high": 250_000, "highText": "EUR 250,000",
           "privacy": "GDPR Article 28 (data processing agreement)",
           "context": "Check the VAT number in VIES and screen against EU sanctions lists during due diligence."},
    "ke": {"tax": "KRA PIN", "pattern": "^[AP][0-9]{9}[A-Z]$",
           "message": "Enter an 11-character KRA PIN, for example P051234567X.",
           "extra": ("agpo", "AGPO certificate holder"), "high": 10_000_000,
           "highText": "KES 10,000,000", "privacy": "Data Protection Act 2019",
           "context": "AGPO vendors are flagged for the public procurement reservation; check the KRA tax compliance certificate."},
}


def vendor_onboarding(region):
    t, c, p = Text(region), VENDOR[region], REGIONS[region]
    cal = calendar(region, "office")
    cur = p["currency"]
    f = Flow()
    f.node("start", "start", "Vendor submitted")
    f.node("risk", "decision", "Rate vendor risk", decisionRef="vendor-risk")
    f.node("split", "parallelSplit", "Run the checks together", joinRef="join")
    f.approval("diligence", t("Procurement due diligence"), "procurement-officers", cal, 24)
    f.approval("bank", t("Verify bank details"), "accounts-payable", cal, 16)
    f.node("join", "parallelJoin", "Both checks finished")
    f.node("privacyRoute", "branch", "Personal data?")
    f.approval("privacy", t("Privacy review"), "privacy-officers", cal, 24)
    f.node("riskRoute", "branch", "High risk?")
    f.approval("compliance", t("Compliance approval"), "compliance-officers", cal, 24)
    f.notify("notifyApproved", "Tell requester the vendor is onboarded", t("The vendor was onboarded."))
    f.notify("notifyRejected", "Tell requester the vendor was rejected", t("The vendor was rejected. Open the request to see the reason."))
    f.end("onboarded", "Onboarded", "Onboarded", "success")
    f.end("rejected", "Rejected", "Rejected", "rejected")
    f.go("start", "risk")
    f.go("risk", "split")
    f.go("split", "diligence")
    f.go("split", "bank")
    f.go("diligence", "join", "approved")
    f.go("diligence", "notifyRejected", "rejected")
    f.go("bank", "join", "approved")
    f.go("bank", "notifyRejected", "rejected")
    f.go("join", "privacyRoute")
    f.go("privacyRoute", "privacy", "condition", "Processes personal data", eq("/input/personalData", True))
    f.go("privacyRoute", "riskRoute", "default", "No personal data")
    f.go("privacy", "riskRoute", "approved")
    f.go("privacy", "notifyRejected", "rejected")
    f.go("riskRoute", "compliance", "condition", f"Conflict declared or {c['highText']} or more", eq("/steps/risk/band", "high"))
    f.go("riskRoute", "notifyApproved", "default", "Standard risk")
    f.go("compliance", "notifyApproved", "approved")
    f.go("compliance", "notifyRejected", "rejected")
    f.go("notifyApproved", "onboarded")
    f.go("notifyRejected", "rejected")
    inputs = {
        "vendorName": string(t("Vendor legal name", 100)),
        "taxId": string(t(c["tax"], 100), 4, 20, pattern=c["pattern"], patternMessage=c["message"]),
        "category": choice(t("Category", 100), ["Goods", "Services", "IT and software", "Works and construction", "Professional services"]),
        "annualSpend": money(t(f"Expected annual spend ({cur})", 100)),
    }
    widgets = {"category": "select", "annualSpend": "number"}
    vendor_fields = ["vendorName", "taxId", "category", "annualSpend"]
    if c["extra"]:
        key, title = c["extra"]
        inputs[key] = flag(t(title, 100))
        widgets[key] = "checkbox"
        vendor_fields.append(key)
    inputs["personalData"] = flag(t("Vendor will process personal data", 100))
    inputs["conflictOfInterest"] = flag(t("Possible conflict of interest declared", 100))
    widgets.update({"personalData": "checkbox", "conflictOfInterest": "checkbox"})
    ui = {"order": list(inputs), "widgets": widgets, "submitLabel": t("Submit vendor", 80),
          "layout": {"sections": [
              {"id": "vendor", "title": t("Vendor"), "columns": 2, "fields": vendor_fields},
              {"id": "risk", "title": t("Risk"), "columns": 1, "fields": ["personalData", "conflictOfInterest"]}]}}
    decisions = [{"key": "vendor-risk", "version": 1, "hitPolicy": "FIRST", "rows": [
        {"when": {"any": [eq("/input/conflictOfInterest", True),
                          {"fieldRef": "/input/annualSpend", "operator": "gte", "value": c["high"]}]}, "then": {"band": "high"}}],
        "default": {"band": "standard"}}]
    outputs = {"riskBand": {"type": "string", "title": "Risk band"},
               "diligenceBy": {"type": "string", "title": "Due diligence by"}}
    mapping = {"riskBand": {"fieldRef": "/steps/risk/band", "required": True},
               "diligenceBy": {"fieldRef": "/steps/diligence/decidedBy", "required": False}}
    desc = (f"{p['name']} vendor onboarding in {cur} without any connection. Procurement due diligence and bank-detail "
            f"verification run in parallel; vendors that process personal data get a privacy review ({c['privacy']}); "
            f"a declared conflict of interest or spend of {c['highText']} or more needs compliance approval. The {c['tax']} "
            f"is format-checked on the form. Due dates count {p['name']} office hours and public holidays. "
            f"{c['context']} {ILLUSTRATIVE}")
    return document(region, "shared-services", "vendor-onboarding", f"vendor-onboarding-{region}",
                    f"Vendor onboarding ({p['name']})", desc, f, inputs,
                    ["vendorName", "taxId", "category", "annualSpend", "personalData", "conflictOfInterest"],
                    ui, decisions, outputs, "Procurement operations", mapping)


# --------------------------------------------------------------------------------------------------------------------
# Public sector: building / business permit
# --------------------------------------------------------------------------------------------------------------------

def permit(region):
    t, p = Text(region), REGIONS[region]
    cal = calendar(region, "office")
    f = Flow()
    f.node("start", "start", "Application received")
    f.node("scope", "decision", "Decide which reviews apply", decisionRef="review-scope")
    f.approval("intake", t("Completeness check"), "permit-clerks", cal, 16)
    if region == "ke":
        return business_permit(region, t, p, cal, f)
    statutory = "P30D" if region == "in" else "P90D"
    fire_label = t("Fire safety NOC") if region == "in" else t("Fire safety review")
    f.approval("planning", t("Planning scrutiny"), "planning-officers", cal, 40)
    f.node("reviews", "parallelSplit", "Specialist reviews", joinRef="reviewsDone", mode="inclusive")
    f.approval("fire", fire_label, "fire-officers", cal, 40)
    f.approval("heritage", t("Heritage review"), "heritage-officers", cal, 40)
    f.node("noReview", "notification", "Note that no specialist review was needed", channel="inApp",
           recipient="initiator", message=t("No specialist review was needed; the application goes to the decision."))
    f.node("reviewsDone", "parallelJoin", "Specialist reviews finished")
    f.approval("decision", t("Permit decision"), "permit-authority", cal, 40, timeoutAfter=statutory)
    chief = "Commissioner decision" if region == "in" else "Head of department decision"
    f.approval("escalation", t(chief), "permit-heads", cal, 24)
    granted = "Your building plan was sanctioned." if region == "in" else "Your building permit was granted."
    f.notify("notifyGranted", "Tell applicant the permit was granted", t(granted))
    f.notify("notifyRefused", "Tell applicant the application was refused", t("Your application was refused. Open the request to see the reason."))
    f.end("granted", "Granted", "Granted", "success")
    f.end("refused", "Refused", "Refused", "rejected")
    f.go("start", "scope")
    f.go("scope", "intake")
    f.go("intake", "planning", "approved")
    f.go("intake", "notifyRefused", "rejected")
    f.go("planning", "reviews", "approved")
    f.go("planning", "notifyRefused", "rejected")
    fire_cond = eq("/steps/scope/fire", True)
    f.go("reviews", "fire", "condition", "High-rise or assembly use", fire_cond)
    f.go("reviews", "heritage", "condition", "Heritage area" if region == "in" else "Listed building", eq("/steps/scope/heritage", True))
    f.go("reviews", "noReview", "default", "No specialist review")
    f.go("fire", "reviewsDone", "approved")
    f.go("fire", "notifyRefused", "rejected")
    f.go("heritage", "reviewsDone", "approved")
    f.go("heritage", "notifyRefused", "rejected")
    f.go("noReview", "reviewsDone")
    f.go("reviewsDone", "decision")
    f.go("decision", "notifyGranted", "approved")
    f.go("decision", "notifyRefused", "rejected")
    f.go("decision", "escalation", "timedOut", "Statutory time limit passed")
    f.go("escalation", "notifyGranted", "approved")
    f.go("escalation", "notifyRefused", "rejected")
    f.go("notifyGranted", "granted")
    f.go("notifyRefused", "refused")
    if region == "in":
        inputs = {
            "applicantName": string(t("Applicant name", 100)),
            "propertyId": string(t("Property ID or survey number", 100), 1, 60),
            "ward": string(t("Ward or zone", 100), 1, 60),
            "buildingUse": choice(t("Building use", 100), ["Residential", "Commercial", "Mixed use", "Institutional", "Assembly"]),
            "plotArea": money(t("Plot area (m²)", 100), 1, 1_000_000),
            "floors": {"type": "integer", "title": t("Floors", 100), "minimum": 1, "maximum": 60},
            "height": money(t("Building height (m)", 100), 1, 300),
            "heritageArea": flag(t("Inside a notified heritage zone", 100)),
            "architect": string(t("Architect registration (CoA)", 100), 6, 30, pattern="^CA/[0-9]{4}/[0-9]{3,6}$",
                                patternMessage="Enter the Council of Architecture number, for example CA/2015/12345."),
        }
        fire_height, statutory_text = 15, "30 days"
        context = ("Fire NOC from 15 m (National Building Code high-rise threshold) or for assembly use; heritage review "
                   "inside a notified heritage zone; escalation to the commissioner after 30 days, where many state "
                   "rules allow deemed sanction.")
        app_fields, bld_fields = ["applicantName", "architect", "propertyId", "ward"], ["buildingUse", "plotArea", "floors", "height", "heritageArea"]
        heritage_ref = "/input/heritageArea"
        title_area = "plotArea"
    else:
        inputs = {
            "applicantName": string(t("Applicant name", 100)),
            "parcel": string(t("Parcel (Flurstück)", 100), 1, 60),
            "district": string(t("Municipality district", 100), 1, 60),
            "buildingUse": choice(t("Building use", 100), ["Residential", "Commercial", "Mixed use", "Industrial", "Assembly"]),
            "floorArea": money(t("Gross floor area (m²)", 100), 1, 1_000_000),
            "floors": {"type": "integer", "title": t("Floors", 100), "minimum": 1, "maximum": 60},
            "height": money(t("Building height (m)", 100), 1, 300),
            "listedBuilding": flag(t("Listed building or heritage area", 100)),
        }
        fire_height, statutory_text = 22, "three months"
        context = ("Fire safety review from 22 m (high-rise threshold in German state building codes) or for assembly use; "
                   "heritage review for listed buildings; escalation after three months, the usual statutory decision "
                   "period. Shown in German as the EU second language; adapt to the member state.")
        app_fields, bld_fields = ["applicantName", "parcel", "district"], ["buildingUse", "floorArea", "floors", "height", "listedBuilding"]
        heritage_ref = "/input/listedBuilding"
        title_area = "floorArea"
    widgets = {"buildingUse": "select", "plotArea": "number", "floorArea": "number", "height": "number",
               "heritageArea": "checkbox", "listedBuilding": "checkbox"}
    ui = {"order": list(inputs), "widgets": {k: v for k, v in widgets.items() if k in inputs},
          "submitLabel": t("Submit application", 80),
          "layout": {"sections": [
              {"id": "applicant", "title": t("Applicant"), "columns": 2, "fields": app_fields},
              {"id": "building", "title": t("Building"), "columns": 2, "fields": bld_fields}]}}
    decisions = [{"key": "review-scope", "version": 1, "hitPolicy": "FIRST", "rows": [
        {"when": {"all": [{"fieldRef": "/input/height", "operator": "gte", "value": fire_height}, eq(heritage_ref, True)]},
         "then": {"fire": True, "heritage": True}},
        {"when": {"all": [eq("/input/buildingUse", "Assembly"), eq(heritage_ref, True)]}, "then": {"fire": True, "heritage": True}},
        {"when": {"fieldRef": "/input/height", "operator": "gte", "value": fire_height}, "then": {"fire": True, "heritage": False}},
        {"when": eq("/input/buildingUse", "Assembly"), "then": {"fire": True, "heritage": False}},
        {"when": eq(heritage_ref, True), "then": {"fire": False, "heritage": True}}],
        "default": {"fire": False, "heritage": False}}]
    outputs = {"fireReview": {"type": "boolean", "title": "Fire review needed"},
               "decidedBy": {"type": "string", "title": "Decided by"}}
    mapping = {"fireReview": {"fieldRef": "/steps/scope/fire", "required": True},
               "decidedBy": {"fieldRef": "/steps/decision/decidedBy", "required": False}}
    kind = "Building plan approval" if region == "in" else "Building permit"
    desc = (f"{p['name']} {kind.lower()} for a municipal office. A clerk checks completeness, planning scrutinises the "
            f"plans, then fire and heritage reviews run only when the rules ask for them (inclusive split), and the permit "
            f"authority decides within {statutory_text} or the case escalates. Due dates count office hours and public "
            f"holidays. {context} {ILLUSTRATIVE}")
    del title_area
    return document(region, "public-sector", "permit", f"permit-{region}", f"{kind} ({p['name']})", desc, f, inputs,
                    ["applicantName", "buildingUse", "floors", "height"] + (["propertyId", "plotArea", "architect", "heritageArea"]
                                                             if region == "in" else ["parcel", "floorArea", "listedBuilding"]),
                    ui, decisions, outputs, "Municipal permit office", mapping)


def business_permit(region, t, p, cal, f):
    f.node("inspections", "parallelSplit", "Inspections", joinRef="inspectionsDone", mode="inclusive")
    f.approval("health", t("Public health inspection"), "health-inspectors", cal, 40)
    f.approval("fire", t("Fire safety inspection"), "fire-officers", cal, 40)
    f.node("noInspection", "notification", "Note that no inspection was needed", channel="inApp", recipient="initiator",
           message=t("No inspection was needed; the application goes to the decision."))
    f.node("inspectionsDone", "parallelJoin", "Inspections finished")
    f.approval("decision", t("Permit decision"), "permit-authority", cal, 24, timeoutAfter="P14D")
    f.approval("escalation", t("Chief officer decision"), "permit-heads", cal, 16)
    f.notify("notifyGranted", "Tell applicant the permit was issued", t("Your single business permit was issued."))
    f.notify("notifyRefused", "Tell applicant the application was refused", t("Your application was refused. Open the request to see the reason."))
    f.end("granted", "Issued", "Issued", "success")
    f.end("refused", "Refused", "Refused", "rejected")
    f.go("start", "scope")
    f.go("scope", "fee")
    f.go("fee", "intake")
    f.go("intake", "inspections", "approved")
    f.go("intake", "notifyRefused", "rejected")
    f.go("inspections", "health", "condition", "Food, drink or lodging", eq("/steps/scope/health", True))
    f.go("inspections", "fire", "condition", "Large premises or hazardous goods", eq("/steps/scope/fire", True))
    f.go("inspections", "noInspection", "default", "No inspection")
    f.go("health", "inspectionsDone", "approved")
    f.go("health", "notifyRefused", "rejected")
    f.go("fire", "inspectionsDone", "approved")
    f.go("fire", "notifyRefused", "rejected")
    f.go("noInspection", "inspectionsDone")
    f.go("inspectionsDone", "decision")
    f.go("decision", "notifyGranted", "approved")
    f.go("decision", "notifyRefused", "rejected")
    f.go("decision", "escalation", "timedOut", "Not decided in 14 days")
    f.go("escalation", "notifyGranted", "approved")
    f.go("escalation", "notifyRefused", "rejected")
    f.go("notifyGranted", "granted")
    f.go("notifyRefused", "refused")
    activities = ["Small trader or shop", "Hotel, restaurant or bar", "Professional services", "Manufacturing",
                  "Hazardous goods: fuel, gas, chemicals", "Transport and storage"]
    inputs = {
        "businessName": string(t("Business name", 100)),
        "kraPin": string(t("KRA PIN", 100), 11, 11, pattern="^[AP][0-9]{9}[A-Z]$",
                         patternMessage="Enter an 11-character KRA PIN, for example P051234567X."),
        "activity": choice(t("Business activity", 100), activities),
        "ward": string(t("Ward", 100), 1, 60),
        "premisesArea": money(t("Premises area (m²)", 100), 1, 100_000),
        "employees": {"type": "integer", "title": t("Employees", 100), "minimum": 0, "maximum": 10000},
    }
    ui = {"order": list(inputs), "widgets": {"activity": "select", "premisesArea": "number"},
          "submitLabel": t("Submit application", 80),
          "layout": {"sections": [
              {"id": "business", "title": t("Business"), "columns": 2, "fields": ["businessName", "kraPin", "activity"]},
              {"id": "premises", "title": t("Premises"), "columns": 2, "fields": ["ward", "premisesArea", "employees"]}]}}
    big = {"fieldRef": "/input/premisesArea", "operator": "gte", "value": 500}
    food = eq("/input/activity", "Hotel, restaurant or bar")
    hazard = eq("/input/activity", "Hazardous goods: fuel, gas, chemicals")
    decisions = [
        {"key": "review-scope", "version": 1, "hitPolicy": "FIRST", "rows": [
            {"when": {"all": [food, big]}, "then": {"health": True, "fire": True}},
            {"when": food, "then": {"health": True, "fire": False}},
            {"when": hazard, "then": {"health": False, "fire": True}},
            {"when": big, "then": {"health": False, "fire": True}}],
         "default": {"health": False, "fire": False}},
        {"key": "permit-fee", "version": 1, "hitPolicy": "FIRST", "rows": [
            {"when": {"fieldRef": "/input/employees", "operator": "gt", "value": 50}, "then": {"feeBand": "Large", "feeKes": 30000}},
            {"when": {"fieldRef": "/input/employees", "operator": "gt", "value": 5}, "then": {"feeBand": "Medium", "feeKes": 15000}}],
         "default": {"feeBand": "Small", "feeKes": 5000}},
    ]
    # The fee is worked out before intake, so every step (and the result) can show it.
    f.nodes.insert(2, {"id": "fee", "type": "decision", "label": "Work out the permit fee", "config": {"decisionRef": "permit-fee"}})
    outputs = {"feeBand": {"type": "string", "title": "Fee band"},
               "feeKes": {"type": "number", "title": "Permit fee (KES)"},
               "decidedBy": {"type": "string", "title": "Decided by"}}
    mapping = {"feeBand": {"fieldRef": "/steps/fee/feeBand", "required": True},
               "feeKes": {"fieldRef": "/steps/fee/feeKes", "required": True},
               "decidedBy": {"fieldRef": "/steps/decision/decidedBy", "required": False}}
    desc = (f"{p['name']} county single business permit. A clerk checks completeness, a rule table sets the fee band "
            f"from the number of employees (KES 5,000, 15,000 or 30,000 as placeholders for the county Finance Act), "
            f"public health and fire inspections run only when the activity or premises need them, and the permit "
            f"authority decides within 14 days or the case goes to the chief officer. Due dates count county office "
            f"hours and Kenyan public holidays. {ILLUSTRATIVE}")
    return document(region, "public-sector", "permit", f"permit-{region}", f"Single business permit ({p['name']})", desc,
                    f, inputs, ["businessName", "kraPin", "activity", "ward", "premisesArea", "employees"], ui, decisions,
                    outputs, "County licensing office", mapping)


# --------------------------------------------------------------------------------------------------------------------
# Public sector: grant application
# --------------------------------------------------------------------------------------------------------------------

GRANT = {
    "in": {"orgTypes": ["Registered NGO", "Self-help group federation", "Gram panchayat", "MSME", "Cooperative society"],
           "reg": ("NGO Darpan ID", "^[A-Z]{2}/[0-9]{4}/[0-9]{7}$", "Enter the NGO Darpan unique ID, for example MH/2019/0234567."),
           "programmes": ["Livelihoods", "Women's empowerment", "Skill development", "Water and sanitation", "Digital literacy"],
           "cap": 5_000_000, "committee": 1_000_000, "minOwn": 10, "committeeText": "INR 10 lakh", "capText": "INR 50 lakh",
           "context": "Built for a state department or CSR foundation; registered NGOs give their NGO Darpan ID."},
    "eu": {"orgTypes": ["Non-profit association", "SME", "Municipality", "Research organisation", "Social enterprise"],
           "reg": ("Register number", "^[A-Za-z0-9 /.-]{3,40}$", "Enter the register number, for example VR 12345."),
           "programmes": ["Climate and energy", "Digital transition", "Social inclusion", "Culture", "Rural development"],
           "cap": 500_000, "committee": 100_000, "minOwn": 20, "committeeText": "EUR 100,000", "capText": "EUR 500,000",
           "context": "Applicants confirm the de minimis ceiling (EUR 300,000 over three years, Regulation 2023/2831); co-funding of at least 20 percent."},
    "ke": {"orgTypes": ["Community-based organisation", "Youth group", "Women group", "Persons with disability group", "Cooperative society"],
           "reg": ("Registration number", "^[A-Za-z0-9 /.-]{3,40}$", "Enter the registration number from the certificate."),
           "programmes": ["Youth enterprise", "Women enterprise", "Agriculture value chains", "Water projects", "Ward development"],
           "cap": 2_000_000, "committee": 500_000, "minOwn": 10, "committeeText": "KES 500,000", "capText": "KES 2,000,000",
           "context": "Built for a county or ward development fund; groups give their registration number from the Social Development office."},
}


def grant(region):
    t, c, p = Text(region), GRANT[region], REGIONS[region]
    cal = calendar(region, "office")
    cur = p["currency"]
    f = Flow()
    f.node("start", "start", "Application received")
    f.node("eligibility", "decision", "Check eligibility", decisionRef="grant-eligibility")
    f.node("eligibleRoute", "branch", "Eligible?")
    f.notify("notifyIneligible", "Tell applicant it is not eligible", t("The application does not meet the programme's eligibility rules."))
    f.node("panel", "approval", t("Technical review panel"), candidateGroupRef="grant-reviewers",
           rejectReasonRequired=True, multiInstance={"approvers": 2})
    f.approval("finance", t("Finance check"), "grants-finance", cal, 24)
    f.node("levelRoute", "branch", "Committee needed?")
    f.approval("committee", t("Grants committee approval"), "grants-committee", cal, 80)
    f.notify("notifyAwarded", "Tell applicant the grant was awarded", t("The grant was awarded."))
    f.notify("notifyRejected", "Tell applicant it was rejected", t("The grant application was rejected. Open the request to see the reason."))
    f.end("awarded", "Awarded", "Awarded", "success")
    f.end("rejected", "Rejected", "Rejected", "rejected")
    f.end("ineligible", "Ineligible", "Ineligible", "rejected")
    f.go("start", "eligibility")
    f.go("eligibility", "eligibleRoute")
    f.go("eligibleRoute", "notifyIneligible", "condition", "Eligibility rule not met", eq("/steps/eligibility/eligible", False))
    f.go("eligibleRoute", "panel", "default", "Eligible")
    f.go("notifyIneligible", "ineligible")
    f.go("panel", "finance", "approved")
    f.go("panel", "notifyRejected", "rejected")
    f.go("finance", "levelRoute", "approved")
    f.go("finance", "notifyRejected", "rejected")
    f.go("levelRoute", "committee", "condition", f"{c['committeeText']} or more",
         {"fieldRef": "/input/requestedAmount", "operator": "gte", "value": c["committee"]})
    f.go("levelRoute", "notifyAwarded", "default", "Below committee threshold")
    f.go("committee", "notifyAwarded", "approved")
    f.go("committee", "notifyRejected", "rejected")
    f.go("notifyAwarded", "awarded")
    f.go("notifyRejected", "rejected")
    reg_title, reg_pattern, reg_message = c["reg"]
    inputs = {
        "organisationName": string(t("Organisation name", 100)),
        "organisationType": choice(t("Organisation type", 100), c["orgTypes"]),
        "registrationId": string(t(reg_title, 100), 3, 40, pattern=reg_pattern, patternMessage=reg_message),
        "programme": choice(t("Programme", 100), c["programmes"]),
        "requestedAmount": money(t(f"Requested amount ({cur})", 100)),
        "ownContribution": {"type": "integer", "title": t("Own contribution (%)", 100), "minimum": 0, "maximum": 100},
        "projectSummary": string(t("Project summary", 100), 20, 4000),
        "budget": {"type": "array", "title": t("Budget lines"), "minItems": 1, "maxItems": 20,
                   "items": {"type": "object", "additionalProperties": False, "required": ["item", "amount"],
                             "properties": {"item": string(t("Budget item", 100), 1, 100),
                                            "amount": money(t(f"Requested amount ({cur})", 100), 1, 100_000_000)}}},
    }
    terms = [{"fieldRef": "/input/requestedAmount", "operator": "gt", "value": c["cap"]},
             {"fieldRef": "/input/ownContribution", "operator": "lt", "value": c["minOwn"]}]
    org_fields = ["organisationName", "organisationType", "registrationId"]
    widgets = {"organisationType": "select", "programme": "select", "requestedAmount": "number", "projectSummary": "textarea"}
    required = ["organisationName", "organisationType", "registrationId", "programme", "requestedAmount", "ownContribution",
                "projectSummary", "budget"]
    if region == "eu":
        inputs["deMinimisExceeded"] = flag(t("Over the de minimis ceiling (3 years)", 100))
        widgets["deMinimisExceeded"] = "checkbox"
        org_fields.append("deMinimisExceeded")
        required.append("deMinimisExceeded")
        terms.append(eq("/input/deMinimisExceeded", True))
    order = list(inputs)
    ui = {"order": order, "widgets": widgets, "submitLabel": t("Submit application", 80),
          "layout": {"sections": [
              {"id": "organisation", "title": t("Organisation"), "columns": 2, "fields": org_fields},
              {"id": "project", "title": t("Project"), "columns": 1,
               "fields": ["programme", "requestedAmount", "ownContribution", "projectSummary", "budget"]}]}}
    rows = [{"when": {"any": terms}, "then": {"eligible": False}}]
    decisions = [{"key": "grant-eligibility", "version": 1, "hitPolicy": "FIRST", "rows": rows, "default": {"eligible": True}}]
    outputs = {"budgetTotal": {"type": "number", "title": f"Budget total ({cur})"},
               "financeCheckedBy": {"type": "string", "title": "Finance checked by"}}
    mapping = {"budgetTotal": {"fieldRef": "/input/budget/sum/amount", "required": True},
               "financeCheckedBy": {"fieldRef": "/steps/finance/decidedBy", "required": False}}
    desc = (f"{p['name']} grant application in {cur}. Rules reject requests above {c['capText']} or with own "
            f"contribution under {c['minOwn']} percent; two reviewers on the technical panel must both approve, finance "
            f"checks the budget, and the grants committee decides from {c['committeeText']}. Due dates count office "
            f"hours and public holidays. {c['context']} {ILLUSTRATIVE}")
    return document(region, "public-sector", "grant", f"grant-application-{region}", f"Grant application ({p['name']})",
                    desc, f, inputs, required, ui, decisions, outputs, "Grants office", mapping)


# --------------------------------------------------------------------------------------------------------------------

BUILDERS = {
    "loan-exception": (loan_exception, ["in", "eu", "ke", "ng"]),
    "kyc-refresh": (kyc_refresh, ["in", "eu", "ke", "ng"]),
    "group-loan": (group_loan, ["in", "ke", "ng"]),
    "vendor-onboarding": (vendor_onboarding, ["in", "eu", "ke"]),
    "permit": (permit, ["in", "eu", "ke"]),
    "grant": (grant, ["in", "eu", "ke"]),
}


# --------------------------------------------------------------------------------------------------------------------
# Sample scenarios (examples/fixtures/pack-scenarios.json): the compiler test dry-runs every one with the simulator,
# and tools/template-packs/e2e_packs.py runs the first one of a template on a live stack.
# visits: steps that must be on the path; skips: steps that must not be.
# --------------------------------------------------------------------------------------------------------------------

AMOUNT = {"in": (500_000, 3_000_000, 12_000_000), "eu": (20_000, 150_000, 600_000),
          "ke": (200_000, 1_500_000, 6_000_000), "ng": (2_000_000, 15_000_000, 60_000_000)}
TAX = {"in": "27AAPFU0939F1ZV", "eu": "DE123456789", "ke": "P051234567X"}
REG = {"in": "MH/2019/0234567", "eu": "VR 12345", "ke": "SD/2021/00456"}


def scenario(name, input, outcome, visits, skips=(), approvals=None):
    out = {"name": name, "input": input, "outcome": outcome, "visits": list(visits), "skips": list(skips)}
    if approvals:
        out["scenario"] = {"approvals": {k: {"outcome": v} for k, v in approvals.items()}}
    return out


def scenarios(kind, region):
    small, mid, large = AMOUNT[region]
    if kind == "loan-exception":
        c = LOAN_EXCEPTION[region]
        base = {"loanReference": "LN-2026-0042", "borrowerSegment": c["segments"][0], "exceptionType": c["exceptions"][0],
                "exceptionDetail": "Rate concession requested to match a competitor offer for a long-standing customer.",
                "relatedParty": False}
        return [
            scenario("within branch powers", {**base, "amount": small}, "Approved", ["branchReview"],
                     ["compliance", "creditHead", "committee"]),
            scenario("credit head level", {**base, "amount": mid}, "Approved", ["branchReview", "creditHead"], ["committee"]),
            scenario("related party at committee level", {**base, "amount": large, "relatedParty": True}, "Approved",
                     ["branchReview", "compliance", "committee"], ["creditHead"]),
            scenario("branch manager rejects", {**base, "amount": small}, "Rejected", ["branchReview", "notifyRejected"],
                     ["notifyApproved"], {"branchReview": "reject"}),
        ]
    if kind == "kyc-refresh":
        c = KYC[region]
        base = {"customerName": "Asha Example", "customerType": "individual", "idDocument": c["docs"][0],
                "lastUpdated": "2018-04-01", "detailsChanged": False}
        return [
            scenario("low risk, no change: self-declaration", {**base, "riskRating": "low"}, "Refreshed", ["notifySelf"],
                     ["review", "edd"]),
            scenario("high risk: review and due diligence", {**base, "riskRating": "high"}, "Refreshed",
                     ["eddReview", "edd", "eddJoin"], ["review", "escalation"]),
            scenario("due diligence not decided in time: escalation", {**base, "riskRating": "high"}, "Refreshed",
                     ["edd", "escalation"], ["review"], {"edd": "timeout"}),
            scenario("changed details, analyst restricts", {**base, "riskRating": "low", "detailsChanged": True},
                     "Restricted", ["review", "notifyRestricted"], ["notifySelf"], {"review": "reject"}),
            scenario("medium risk: analyst review", {**base, "customerType": "corporate", "riskRating": "medium"},
                     "Refreshed", ["review"], ["notifySelf", "edd"]),
        ]
    if kind == "group-loan":
        c = GROUP[region]
        kind0, lo, _ = c["rules"][0]

        def members(n, each):
            return [{"memberName": f"Member {i + 1}", "memberRef": f"M-{i + 1:03d}", "amount": each} for i in range(n)]
        base = {"groupName": "Umoja Traders", "groupType": kind0, "registered": True, "meetingDate": "2026-10-15",
                "loanPurpose": c["purposes"][0]}
        each_small = 50_000 if region != "ng" else 500_000
        each_large = c["committee"] // lo + 1
        return [
            scenario("eligible group within branch powers", {**base, "members": members(lo, each_small)}, "Approved",
                     ["fieldVerification", "branchApproval"], ["committee", "notifyIneligible"]),
            scenario("too few members", {**base, "members": members(lo - 1, each_small)}, "Ineligible",
                     ["notifyIneligible"], ["fieldVerification"]),
            scenario("not registered", {**base, "registered": False, "members": members(lo, each_small)}, "Ineligible",
                     ["notifyIneligible"], ["fieldVerification"]),
            scenario("large total goes to committee", {**base, "members": members(lo, each_large)}, "Approved",
                     ["fieldVerification", "committee"], ["branchApproval"]),
        ]
    if kind == "vendor-onboarding":
        base = {"vendorName": "Example Supplies Ltd", "taxId": TAX[region], "category": "Goods", "personalData": False,
                "conflictOfInterest": False}
        return [
            scenario("standard vendor", {**base, "annualSpend": small}, "Onboarded", ["diligence", "bank", "join"],
                     ["privacy", "compliance"]),
            scenario("personal data and conflict of interest",
                     {**base, "annualSpend": small, "personalData": True, "conflictOfInterest": True}, "Onboarded",
                     ["privacy", "compliance"]),
            scenario("high spend needs compliance", {**base, "annualSpend": VENDOR[region]["high"]}, "Onboarded",
                     ["compliance"], ["privacy"]),
            scenario("bank details not verified", {**base, "annualSpend": small}, "Rejected", ["bank", "notifyRejected"],
                     ["notifyApproved"], {"bank": "reject"}),
        ]
    if kind == "permit" and region == "ke":
        base = {"businessName": "Mama Njeri Kiosk", "kraPin": TAX["ke"], "ward": "Kilimani"}
        return [
            scenario("small shop: no inspection",
                     {**base, "activity": "Small trader or shop", "premisesArea": 20, "employees": 2},
                     "Issued", ["intake", "fee", "noInspection", "decision"], ["health", "fire"]),
            scenario("large restaurant: both inspections",
                     {**base, "activity": "Hotel, restaurant or bar", "premisesArea": 800, "employees": 60}, "Issued",
                     ["health", "fire"], ["noInspection"]),
            scenario("decision not taken in 14 days",
                     {**base, "activity": "Professional services", "premisesArea": 40, "employees": 8},
                     "Issued", ["decision", "escalation"], approvals={"decision": "timeout"}),
            scenario("incomplete application",
                     {**base, "activity": "Small trader or shop", "premisesArea": 20, "employees": 2},
                     "Refused", ["intake", "notifyRefused"], ["health", "fire", "decision"], {"intake": "reject"}),
        ]
    if kind == "permit":
        if region == "in":
            base = {"applicantName": "R. Sharma", "propertyId": "SY-112/4", "ward": "Ward 14", "plotArea": 300,
                    "floors": 3, "architect": "CA/2015/12345", "heritageArea": False}
            heritage = "heritageArea"
        else:
            base = {"applicantName": "M. Muster", "parcel": "Flur 3, Flurstück 112/4", "district": "Mitte",
                    "floorArea": 450, "floors": 3, "listedBuilding": False}
            heritage = "listedBuilding"
        return [
            scenario("house: no specialist review", {**base, "buildingUse": "Residential", "height": 10}, "Granted",
                     ["intake", "planning", "noReview", "decision"], ["fire", "heritage"]),
            scenario("high-rise in heritage area: both reviews",
                     {**base, "buildingUse": "Mixed use", "height": 30, "floors": 9, heritage: True}, "Granted",
                     ["fire", "heritage"], ["noReview"]),
            scenario("statutory time limit passed", {**base, "buildingUse": "Residential", "height": 10}, "Granted",
                     ["decision", "escalation"], approvals={"decision": "timeout"}),
            scenario("planning refuses", {**base, "buildingUse": "Commercial", "height": 12}, "Refused",
                     ["planning", "notifyRefused"], ["decision"], {"planning": "reject"}),
        ]
    if kind == "grant":
        c = GRANT[region]
        base = {"organisationName": "Example Community Trust", "organisationType": c["orgTypes"][0],
                "registrationId": REG[region], "programme": c["programmes"][0], "ownContribution": 25,
                "projectSummary": "Training and seed capital for twenty women-led household enterprises."}
        if region == "eu":
            base["deMinimisExceeded"] = False
        under = c["committee"] // 2
        return [
            scenario("below committee threshold",
                     {**base, "requestedAmount": under, "budget": [{"item": "Training", "amount": under}]},
                     "Awarded", ["panel", "finance"], ["committee", "notifyIneligible"]),
            scenario("above the programme cap",
                     {**base, "requestedAmount": c["cap"] + 1, "budget": [{"item": "Equipment", "amount": c["cap"] + 1}]},
                     "Ineligible", ["notifyIneligible"], ["panel"]),
            scenario("own contribution too low",
                     {**base, "ownContribution": c["minOwn"] - 1, "requestedAmount": under,
                      "budget": [{"item": "Training", "amount": under}]},
                     "Ineligible", ["notifyIneligible"], ["panel"]),
            scenario("committee decides",
                     {**base, "requestedAmount": c["committee"],
                      "budget": [{"item": "Training", "amount": c["committee"] // 2},
                                 {"item": "Equipment", "amount": c["committee"] // 2}]},
                     "Awarded", ["panel", "finance", "committee"]),
            scenario("panel reviewer rejects",
                     {**base, "requestedAmount": under, "budget": [{"item": "Training", "amount": under}]},
                     "Rejected", ["panel", "notifyRejected"], ["finance"], {"panel": "reject"}),
        ]
    raise KeyError(kind)


def render(doc):
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def all_files():
    files, fixtures = {}, {}
    for kind, (build, regions) in BUILDERS.items():
        for region in regions:
            files[OUT / f"pack-{region}-{kind}.json"] = render(build(region))
            fixtures[f"pack-{region}-{kind}"] = scenarios(kind, region)
    files[OUT / "fixtures" / "pack-scenarios.json"] = render({
        "description": "Generated by tools/template-packs/generate_packs.py. Sample requests for every regional pack "
                       "template: the simulator must reach outcome, pass every step in visits and none in skips.",
        "templates": dict(sorted(fixtures.items()))})
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="only compare; exit 1 when a file is out of date")
    args = parser.parse_args()
    files = all_files()
    stale = [p for p in sorted(OUT.glob("pack-*.json")) if p not in files]
    differs = [p for p, text in files.items() if not p.is_file() or p.read_text(encoding="utf-8") != text]
    if args.check:
        for p in differs + stale:
            print(f"out of date: {p.relative_to(ROOT)}")
        print(f"{len(files)} pack templates, {len(differs) + len(stale)} out of date")
        return 1 if differs or stale else 0
    for p, text in files.items():
        p.write_text(text, encoding="utf-8", newline="\n")
    for p in stale:
        p.unlink()
    print(f"wrote {len(files)} pack templates")
    return 0


if __name__ == "__main__":
    sys.exit(main())
