package i2p.keytools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.AESEngine;
import net.i2p.crypto.DSAEngine;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;

import static org.junit.Assert.assertTrue;


public class PrivateKey extends PrivateKeyFile {

    final private I2PAppContext ctx = I2PAppContext.getGlobalContext();

    public PrivateKey(File key) {
        super(key);        
    }

    public Destination assureDestination() throws DataFormatException, IOException, I2PException {
        Destination dest = null;
        try {
            dest = this.getDestination();
        } catch(I2PSessionException ex) {

        } catch(FileNotFoundException ex) {}

        if(dest==null) {
            this.createIfAbsent();
            this.setHashCashCert(20);
            this.write(); // DO NOT FORGET TO WRITE AARGH
            dest = this.getDestination();
            if(dest==null)
                throw new RuntimeException("Could not create private key "+this.toString());
        }
        return dest;
    }    

    public SignatureHash sign(InputStream in) throws IOException {
        try {
            this.assureDestination();
        } catch (DataFormatException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
        } catch (I2PException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
        }
        SigningPrivateKey skey = this.getSigningPrivKey();
        assertTrue(skey != null);	
        return new SignatureHash(Tools.calculateHash(in), skey);
    }

    // XXX: call this detachedSign?
    public void sign(InputStream in, OutputStream out) throws IOException, DataFormatException {
        SignatureHash sig = sign(in);
        assertTrue(sig != null);
        sig.writeBytes(out);
    }

    public void blockSign(InputStream in, final JarOutputStream out) throws IOException {
        JarEntry je = new JarEntry("id");
        out.putNextEntry(je);
        try {
            assureDestination().calculateHash().writeBytes(out);
        } catch (DataFormatException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        } catch (I2PException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        }
        out.closeEntry();

        KeyFinder.tempit(in,false,new InputHandler() {
            public boolean handle(File temp, FileInputStream in) throws IOException {
                SignatureHash sig = sign(in);
                JarEntry je = new JarEntry("signature");
                out.putNextEntry(je);
                sig.writeBytes(out);
                out.closeEntry();
                in.close();
                in = new FileInputStream(temp);
                je = new JarEntry("body");
                out.putNextEntry(je);
                byte[] buf = new byte[0x1000];
                for (;;) {
                    int amount = in.read(buf);
                    if (amount <= 0) break;
                    out.write(buf, 0, amount);
                }
                out.closeEntry();
                out.close();
                return true;
            }
        });
    }
   
    static void decrypt(InputStream in, OutputStream out) throws IOException {
        Hash id = new Hash();
        try {
            id.readBytes(in);
        } catch (DataFormatException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }
        final PrivateKey self;

        try {
            self = KeyFinder.find(id).privateKey;
        } catch (DataFormatException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            return;
        } catch (I2PSessionException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            return;
        } catch (I2PException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }

        if(self==null)
            throw new RuntimeException("No private key for id "+id);

        try {
            self.assureDestination();
        } catch (DataFormatException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            return;
        } catch (I2PException ex) {
            Logger.getLogger(PrivateKey.class.getName()).log(Level.SEVERE, null, ex);
            return;
        }

        net.i2p.data.PrivateKey pkey = self.getPrivKey();
        SessionKey sess = new SessionKey();
        byte[] edata = new byte[514];
        if (514 != in.read(edata)) {
            throw new RuntimeException("Huh?");
        }
        byte[] data = self.ctx.elGamalEngine().decrypt(edata, pkey);
        if (data == null) {
            throw new RuntimeException("You suck bobba!");
        }
        final byte[] sessblugh = Arrays.copyOfRange(data, 0, SessionKey.KEYSIZE_BYTES);
        sess.setData(sessblugh);
        byte[] iv = new byte[0x10];
        System.arraycopy(iv, 0, data, SessionKey.KEYSIZE_BYTES, 0x10);
        data = new byte[0x1000];
        AESEngine aes = self.ctx.aes();
        byte[] buf = new byte[0x1000];

        for (;;) {
            int amount = in.read(data);
            if (amount <= 0) {
                break;
            }
            aes.decrypt(data, 0, buf, 0, sess, iv, amount);
            byte adjustment = buf[0];
            out.write(buf, 1, amount - adjustment);
        }
    }

    void export(PrintStream out) {
        // how to export securely?
        throw new UnsupportedOperationException("Not yet implemented");
    }
}