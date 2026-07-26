@CloudstreamPlugin                                      ✅ annotation marker
class OppaDramaPlugin: Plugin() {                       ✅ extends Plugin
    override fun load(context: Context) {               ✅ signature dengan Context
        registerMainAPI(OppaDramaProvider())            ✅ provider
        registerExtractorAPI(Smoothpre())               ✅ alias EarnVids
        registerExtractorAPI(BuzzServer())              ✅ overrider BuzzHeavier
        registerExtractorAPI(EmturbovidExtractor())     ✅ emturbovid
        registerExtractorAPI(AbyssExtractor())          ✅ abyss.to
        registerExtractorAPI(MinochinosExtractor())     ✅ minochinos
    }
}
