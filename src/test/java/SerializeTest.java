import com.android.studio.ml.modelproviders.data.OpenAiApiType;
import com.android.studio.ml.modelproviders.data.ProviderData;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;

public class SerializeTest {
    public static void main(String[] args) throws Exception {
        ProviderData.RemoteProviderData d = new ProviderData.RemoteProviderData(
                "https://api.openai.com/v1", "", "Authorization",
                ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        d.setOpenAiApiType(OpenAiApiType.CHAT_COMPLETION);
        Element el = XmlSerializer.serialize(d);
        System.out.println("serialized: " + new org.jdom.output.XMLOutputter().outputString(el));

        ProviderData.RemoteProviderData d2 = XmlSerializer.deserialize(el, ProviderData.RemoteProviderData.class);
        System.out.println("restored openAiApiType=" + d2.getOpenAiApiType());
        System.out.println("restored schema=" + d2.getSchema());
        System.out.println("restored url=" + d2.getUrl());

        ProviderData.RemoteProviderData d3 = new ProviderData.RemoteProviderData();
        System.out.println("ctor default=" + d3.getOpenAiApiType());

        ProviderData.RemoteProviderData c = d.copy();
        System.out.println("copy() keeps field=" + c.getOpenAiApiType());
        ProviderData.RemoteProviderData c4 = d.copy("u", "k", "h", ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        System.out.println("copy(4args) keeps field=" + c4.getOpenAiApiType());

        check(d.equals(c), "copy() equals original");
        check(d.hashCode() == c.hashCode(), "copy() hashCode equals");
        ProviderData.RemoteProviderData same = new ProviderData.RemoteProviderData(
                "https://api.openai.com/v1", "", "Authorization",
                ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        same.setOpenAiApiType(OpenAiApiType.CHAT_COMPLETION);
        check(d.equals(same), "same fields incl. openAiApiType -> equals");
        same.setOpenAiApiType(OpenAiApiType.RESPONSE);
        check(!d.equals(same), "only openAiApiType differs -> NOT equals (isModified 检测的关键)");
        System.out.println("info: hashCode d=" + d.hashCode() + " same=" + same.hashCode()
                + (d.hashCode() != same.hashCode() ? " (differs)" : " (collides, allowed)"));
        ProviderData.RemoteProviderData restored = XmlSerializer.deserialize(el, ProviderData.RemoteProviderData.class);
        check(d.equals(restored), "serialized->deserialized equals original");
        System.out.println("ALL_OK");
    }

    static void check(boolean cond, String msg) {
        if (!cond) {
            System.out.println("FAILED: " + msg);
            System.exit(1);
        }
        System.out.println("ok: " + msg);
    }
}
