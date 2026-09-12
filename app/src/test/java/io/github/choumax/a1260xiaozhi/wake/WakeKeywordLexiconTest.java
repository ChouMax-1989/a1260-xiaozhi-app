package io.github.choumax.a1260xiaozhi.wake;
import java.io.StringReader;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class WakeKeywordLexiconTest {
    @Test public void phraseReadingWinsOverSingleCharacter() throws Exception {
        String lexicon="重\tzh ong\n庆\tq ing\n重庆\tch ong q ing\n";
        assertEquals("ch ong q ing @wake\n",WakeKeywordLexicon.encode(new StringReader(lexicon),"重庆"));
    }
    @Test(expected=IllegalArgumentException.class) public void unsupportedCharacterIsRejected() throws Exception {
        WakeKeywordLexicon.encode(new StringReader("你\tn i\n"),"你好");
    }
    @Test(expected=IllegalArgumentException.class) public void nativeSyntaxCannotBeInjected() throws Exception {
        WakeKeywordLexicon.encode(new StringReader(""),"你好/@bad");
    }
    @Test public void packagedDictionaryEncodesCustomPhraseAndPolyphonicWord() throws Exception {
        String[][] cases={{"你好小智","n ǐ h ǎo x iǎo zh ì @wake\n"},
                {"你好周三","n ǐ h ǎo zh ōu s ān @wake\n"},
                {"重庆","ch óng q ìng @wake\n"},
                {"女儿","n ǚ ér @wake\n"}};
        for(String[] test:cases) {
            java.io.Reader source=java.nio.file.Files.newBufferedReader(
                    java.nio.file.Path.of("src/main/assets/kws/lexicon.tsv"),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(test[1],WakeKeywordLexicon.encode(source,test[0]));
        }
    }
}
