package dev.shoam.streaks;

import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link LuckPermsGraceOverrideResolver}'s grace override resolution.
 *
 * <p>The resolver picks the <em>highest</em> {@code shamplugin.graces.<number>} permission
 * granted to a player (so higher ranks that inherit lower ones win), and returns -1 when no
 * such permission is present. The private map-based method is exercised directly via
 * reflection because there is no public seam for it.
 */
class LuckPermsGraceOverrideResolverTest {

    @SuppressWarnings("unchecked")
    private static int resolve(Map<String, Boolean> perms) throws Exception {
        Constructor<LuckPermsGraceOverrideResolver> ctor = LuckPermsGraceOverrideResolver.class
                .getDeclaredConstructor(UserManager.class);
        ctor.setAccessible(true);
        // userManager is unused by the pure map-based resolver path; null is safe here.
        LuckPermsGraceOverrideResolver instance = ctor.newInstance(new Object[]{null});

        Method m = LuckPermsGraceOverrideResolver.class.getDeclaredMethod("resolveGraceOverride", Map.class);
        m.setAccessible(true);
        return (int) m.invoke(instance, perms);
    }

    private static Map<String, Boolean> mapOf(Object... kv) {
        Map<String, Boolean> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Boolean) kv[i + 1]);
        }
        return m;
    }

    @Test
    void highestValueWins() throws Exception {
        assertEquals(4, resolve(mapOf(
                "shamplugin.graces.2", true,
                "shamplugin.graces.3", true,
                "shamplugin.graces.4", true)));
    }

    @Test
    void singleValueIsReturned() throws Exception {
        assertEquals(5, resolve(mapOf("shamplugin.graces.5", true)));
    }

    @Test
    void noMatchingPermissionReturnsMinusOne() throws Exception {
        assertEquals(-1, resolve(new HashMap<>()));
    }

    @Test
    void onlyNonGracePermissionsReturnMinusOne() throws Exception {
        assertEquals(-1, resolve(mapOf("shamplugin.streak", true, "some.other.perm", true)));
    }

    @Test
    void explicitZeroIsDistinctFromNoOverride() throws Exception {
        assertEquals(0, resolve(mapOf("shamplugin.graces.0", true)));
    }

    @Test
    void falseValuesAreIgnored() throws Exception {
        assertEquals(2, resolve(mapOf("shamplugin.graces.9", false, "shamplugin.graces.2", true)));
    }

    @Test
    void nonNumericSuffixIsIgnored() throws Exception {
        assertEquals(3, resolve(mapOf("shamplugin.graces.foo", true, "shamplugin.graces.3", true)));
    }

    @Test
    void highestWinsOverJunkAndFalseValues() throws Exception {
        assertEquals(7, resolve(mapOf(
                "shamplugin.graces.7", true,
                "shamplugin.graces.bad", true,
                "other.perm", false,
                "shamplugin.graces.1", true)));
    }

    @Test
    void caseInsensitiveMatching() throws Exception {
        assertEquals(6, resolve(mapOf("ShamPlugin.Graces.6", true)));
    }
}
