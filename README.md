# Så simulerade jag Captor Iris Bonds avkastning *före* fonden fanns

Iris Bond startade 2017, men jag ville se hur den troligen hade betett sig längre tillbaka – inte minst genom finanskrisen och räntefallet dessförinnan. Problemet: ingen vanlig räntefond liknar Iris. Den har extremt lång ränterisk (duration ~10–15 år) via ränteswappar ovanpå ett ben av svenska säkerställda obligationer, så man kan inte bara låna historiken från en befintlig fond. Istället byggde jag med hjälp av Claude en **modell** av vad som driver fondens avkastning och extrapolerade bakåt. Så här gick det till.

## Grundidén

Captor Iris Bond baseras på investeringar främst i säkerställda bostadsobligationer, men också en del statsobligationer och liknande (fondens obligationsben). Ovanpå det används räntederivat (swappar och swaptions) för att förlänga löptiden och upprätthålla exponering mot svenska räntor med lång löptid.

Fondens avkastning styrs i princip av tre marknadsfaktorer, plus fondens kostnader:

1. **Ränterörelser** – när räntan på den löptid fonden är exponerad mot faller stiger fonden (och vice versa).
2. **Covered-spreaden** – hur mycket mer säkerställda (bostads-)obligationer ger än staten.
3. **Löpande ränta (carry)** – kupongen man får på innehavda obligationer + carry i swapparna.
4. **Kostnader** – förvaltningsavgift och övriga kostnader (transaktion/administration) drar ner avkastningen med en i stort sett konstant årlig kostnad.

Utöver faktorerna ovan påverkas avkastningen av **aktiva förvaltningsbeslut** (se förbehåll) och av att fonden använder **swaptions** för att hantera durationen – det senare ger en känslighet för *hur mycket* räntan rör sig (räntevolatilitet), inte bara riktningen, en sekundär effekt som modellen inte fångar.

Jag mätte hur känslig fondens veckoavkastning är mot var och en av de tre marknadsfaktorerna, via en regressionsanalys under fondens livstid (2017 till idag), och applicerade sedan de känsligheterna på **historiska räntedata tillbaka till 1980-talet**.

Att jag mäter på **veckoavkastning** och inte dagsavkastning är medvetet (se förbehåll).

## Faktorerna

- **Ränta:** förändringar i 5- och 10-åriga svenska räntan (två "nyckelrater" fångar durationen bättre än bara en).
- **Covered-spread:** 5-årig bostadsobligationsränta minus 5-årig statsränta, och dess förändring.
- **Carry:** räntenivån + spreaden, som en *nivåterm* (inte en konstant). Det är viktigt – det gör att carryn i simuleringen automatiskt blir mycket högre på 90-talet, när räntorna låg på 8–12 %.

Regressionsanalysen ger då fondens löptid (~9,7 år enligt denna, vilket är lägre än de ca 11 år som anges, se förbehåll nedan), obligationsbenets spread-känslighet och en carry-koefficient. Passningen är god (modellens simulerade "fond" har en korrelationsfaktor på ca 0,97 jämfört med den riktiga fonden sedan 2017).

En genväg som försämrar simuleringen är att jag använt statsränta för att approximera swapräntan. Iris förlänger sin duration med **ränteswappar**, så egentligen borde räntefaktorn vara **10-årig swapränta**. Men jag har inte **swapräntehistorik så långt tillbaka** som jag behöver (till 80-/90-talet), medan Riksbanken har **statsobligationsräntor** ända sedan mitten av 80-talet. Statsränta och swapränta rör sig nästan i takt, så jag använder **statsräntan som ersättare**.

**När blir det ett problem?** Skillnaden mellan swap och stat (”swapspreaden”) är inte konstant – och den rör sig mest **under kriser och i efterdyningarna av dem**:

- I stress får statsobligationer ofta en *flight-to-safety*-effekt (alla vill äga staten → statsräntan pressas ner extra), medan swapräntan styrs mer av bank- och kreditförhållanden. Ibland går det åt andra hållet, t.ex. när stora statslåneemissioner pressar upp statsräntan medan swappar inte påverkas lika mycket (som delvis 2022–2023).
- Oavsett riktning: **just i kriserna divergerar swap och stat tillfälligt**, och sedan lägger sig spreaden på en ny nivå.

Eftersom min modell drivs av statsräntan men fonden i verkligheten av swappen, blir det alltså **precis i de mest intressanta perioderna** (2008, 2011–12, 2022) som bakåtsimuleringen riskerar att avvika mest från hur Iris faktiskt hade betett sig. Lite extra ironiskt, eftersom det ofta är just krisbeteendet man vill förstå hos en sådan här ”försäkrings”-fond. Tolka därför de skarpaste krisrörelserna i den simulerade historiken med en nypa salt.

## Övriga förbehåll

- Fonden är aktivt förvaltad. Aktiva beslut kan förstås orsaka avvikelser från förväntad avkastning.
- **Kostnader:** avkastningen är netto efter avgifter. Fondens NAV är redan efter kostnader; i simuleringen adderas den kända avgiften tillbaka *före* regressionen (så att interceptet blir fondens residual/alfa, inte avgift) och dras sedan av explicit igen.
- Analysen bygger på **veckoavkastning**, inte dagsavkastning. Dagliga NAV-förändringar är stökiga: trög/icke-synkron prissättning gör att en dags ränterörelse kan hamna snett mot fondens dagsavkastning, och ovanpå det finns mätbrus (NAV avrundas, kupong/avgift periodiseras). Det skulle dra ner den skattade durationen och ge kvarvarande autokorrelation. Veckofönstret jämnar ut det – priset är färre observationer.
- Modellen antar **konstant duration** över tiden, men i verkligheten justeras den. De ~9,7 åren är ett snitt och något lägre än fondens mandat (~10–15 år), delvis just på grund av swap-vs-stat-approximationen ovan.
- En mindre del av innehavet är **statspapper** snarare än säkerställda obligationer. Det är i praktiken oproblematiskt – den delen ligger direkt på statskurvan som jag redan använder som räntefaktor (ingen swap-vs-stat-basis, och per definition ingen covered-spread). Konsekvensen är bara att obligationsbenets skattade spread-känslighet (~4 år) är ett *portföljsnitt* över blandningen stat + covered, alltså något utspädd mot ett rent covered-innehav – ännu ett skäl att läsa koefficienterna som genomsnitt.
- Fonden använder även **swaptions** för att upprätthålla durationen. Optioner är icke-linjära och känsliga för **räntevolatilitet** – en exponering modellen inte fångar (den har ingen volatilitetsfaktor). Effekten är störst i krisperioder med volatilitetstoppar, och gör den konstanta durationen till en grövre approximation över olika ränteregimer.
- **Före 2006** var svenska bostadsobligationer inte formellt *säkerställda*. Om vi antar att fonden då ändå skulle ha ägt bostadsobligationer så är detta inget större problem för hur väl simuleringen fångar fondens utveckling.

## Slutsats

Resultatet är en sammanhängande, simulerad totalavkastning för Iris Bond från 1987, byggd på svenska stats- och bostadsräntor. Den fångar det stora draget – det långa räntefallet och hur en långduration-fond gynnas av det – men ska ses som en **kvalificerad uppskattning**, med störst osäkerhet just i kris- och regimskiftesperioder.

Så här ser utvecklingen för simuleringen ut jämfört med faktisk utveckling under fondens livstid:

<Här ska diagram in>

Avvikelser finns som förväntat, men det generella mönstret är ok.

@captorfonder Har ni någon kommentar om detta? Har ni någon bättre historisk simulering ni kan dela med er av?

CSV med de simulerade värdena för Captor Iris Bond A och Captor Iris Bond B finner ni här: <Länk kommer läggas till>  

Jag tar inget som helst ansvar för att sifforna är korrekta eller ens rimliga! Gör er egen verifiering, det kan ha smugit sig in massa fel.