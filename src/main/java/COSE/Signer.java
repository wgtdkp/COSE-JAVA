/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package COSE;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;
import static java.lang.Integer.min;
import java.math.BigInteger;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;

/**
 *
 * @author jimsch
 */
public class Signer extends Attribute {
    protected byte[] rgbSignature;
    protected byte[] rgbProtected;
    protected String contextString;
    CBORObject cnKey;
    
    public Signer() {
        contextString = "Signature";
    }
    
    public void setKey(CBORObject cnKeyIn) {
        cnKey = cnKeyIn;
    }
    
    protected void DecodeFromCBORObject(CBORObject obj) throws CoseException {
        if (obj.getType() != CBORType.Array) throw new CoseException("Invalid Signer structure");
        
        if (obj.size() != 3) throw new CoseException("Invalid Signer structure");
        
        if (obj.get(0).getType() == CBORType.ByteString) {
            if (obj.get(0).GetByteString().length == 0) {
                objProtected = CBORObject.NewMap();
                rgbProtected = new byte[0];
            }
            else {
                rgbProtected = obj.get(0).GetByteString();
                objProtected = CBORObject.DecodeFromBytes(rgbProtected);
                if (objProtected.size() == 0) rgbProtected = new byte[0];
            }
        }
        else throw new CoseException("Invalid Signer structure");
        
        if (obj.get(1).getType() == CBORType.Map) {
            objUnprotected = obj.get(1);
        }
        else throw new CoseException("Invalid Signer structure");
        
        if (obj.get(2).getType() == CBORType.ByteString) rgbSignature = obj.get(2).GetByteString();
        else if (!obj.get(2).isNull()) throw new CoseException("Invalid Signer structure");
    }    
    
    protected CBORObject EncodeToCBORObject() {
        CBORObject obj = CBORObject.NewArray();
        
        obj.Add(rgbProtected);
        obj.Add(objUnprotected);
        obj.Add(rgbSignature);
        
        return obj;
    }

    public void sign(byte[] rgbBodyProtected, byte[] rgbContent) throws CoseException
    {
        if (rgbProtected == null) {
            if(objProtected.size() == 0) rgbProtected = new byte[0];
            else rgbProtected = objProtected.EncodeToBytes();
        }
        
        CBORObject obj = CBORObject.NewArray();
        obj.Add(contextString);
        obj.Add(rgbBodyProtected);
        obj.Add(rgbProtected);
        obj.Add(externalData);
        obj.Add(rgbContent);

        AlgorithmID alg = AlgorithmID.FromCBOR(findAttribute(HeaderKeys.Algorithm));
        
        rgbSignature = Signer.computeSignature(alg, obj.EncodeToBytes(), cnKey);                
    }
    
    public boolean validate(byte[] rgbBodyProtected, byte[] rgbContent) throws CoseException
    {
        CBORObject obj = CBORObject.NewArray();
        obj.Add(contextString);
        obj.Add(rgbBodyProtected);
        obj.Add(rgbProtected);
        obj.Add(externalData);
        obj.Add(rgbContent);

        AlgorithmID alg = AlgorithmID.FromCBOR(findAttribute(HeaderKeys.Algorithm));

        return Signer.validateSignature(alg, obj.EncodeToBytes(), rgbSignature, cnKey);        
    }
    
    static byte[] computeSignature(AlgorithmID alg, byte[] rgbToBeSigned, CBORObject cnKey) throws CoseException {
        Digest digest;
        CBORObject cn;

        switch (alg) {
            case ECDSA_256:
                digest = new SHA256Digest();
                break;
            
            case ECDSA_384:
                digest = new SHA384Digest();
                break;
                
            case ECDSA_512:
                digest = new SHA512Digest();
                break;
            
            default:
                throw new CoseException("Unsupported Algorithm Specified");
        }
        
        switch (alg) {
            case ECDSA_256:
            case ECDSA_384:
            case ECDSA_512:
            {
                digest.update(rgbToBeSigned, 0, rgbToBeSigned.length);
                byte[] rgbDigest = new byte[digest.getDigestSize()];
                digest.doFinal(rgbDigest, 0);
                
                cn = cnKey.get(KeyKeys.KeyType.AsCBOR());
                if ((cn == null) || (cn != KeyKeys.KeyType_EC2)) throw new CoseException("Must use key with key type EC2");
                cn = cnKey.get(KeyKeys.EC2_D.AsCBOR());
                if (cn == null) throw new CoseException("Private key required to sign");
                
                X9ECParameters p = SignCommon.GetCurve(cnKey);
                ECDomainParameters parameters = new ECDomainParameters(p.getCurve(), p.getG(), p.getN(), p.getH());
                ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(new BigInteger(1, cn.GetByteString()), parameters);
                
                ECDSASigner ecdsa = new ECDSASigner();
                ecdsa.init(true, privKey);
                BigInteger[] sig = ecdsa.generateSignature(rgbDigest);
                
                int cb = (p.getCurve().getFieldSize() + 7)/8;
                byte[] r = sig[0].toByteArray();
                byte[] s = sig[1].toByteArray();
                
                byte[] sigs = new byte[cb*2];
                int cbR = min(cb,r.length);
                System.arraycopy(r, r.length - cbR, sigs, cb - cbR, cbR);
                cbR = min(cb, s.length);
                System.arraycopy(s, s.length - cbR, sigs, cb + cb - cbR, cbR);

                return sigs;                
            }
            
            default:
                throw new CoseException("Inernal error");
        }
    }

    static boolean validateSignature(AlgorithmID alg, byte[] rgbToBeSigned, byte[] rgbSignature, CBORObject cnKey) throws CoseException {
        Digest digest;
        
        switch (alg) {
            case ECDSA_256:
                digest = new SHA256Digest();
                break;
            
            case ECDSA_384:
                digest = new SHA384Digest();
                break;
                
            case ECDSA_512:
                digest = new SHA512Digest();
                break;
            
            default:
                throw new CoseException("Unsupported algorithm specified");
        }
        
        switch (alg) {
            case ECDSA_256:
            case ECDSA_384:
            case ECDSA_512:
            {
                byte[] rgbR = new byte[rgbSignature.length/2];
                byte[] rgbS = new byte[rgbSignature.length/2];
                System.arraycopy(rgbSignature, 0, rgbR, 0, rgbR.length);
                System.arraycopy(rgbSignature, rgbR.length, rgbS, 0, rgbR.length);
                
                digest.update(rgbToBeSigned, 0, rgbToBeSigned.length);
                byte[] rgbDigest = new byte[digest.getDigestSize()];
                digest.doFinal(rgbDigest, 0);
                
                X9ECParameters p = SignCommon.GetCurve(cnKey);
                ECDomainParameters parameters = new ECDomainParameters(p.getCurve(), p.getG(), p.getN(), p.getH());
                BigInteger bnX = new BigInteger(1, cnKey.get(KeyKeys.EC2_X.AsCBOR()).GetByteString());
                ECPoint point = p.getCurve().createPoint(bnX, new BigInteger(1, cnKey.get(KeyKeys.EC2_Y.AsCBOR()).GetByteString()));
                
                ECPublicKeyParameters pubKey = new ECPublicKeyParameters(point, parameters);
                
                ECDSASigner ecdsa = new ECDSASigner();
                ecdsa.init(false, pubKey);
                return ecdsa.verifySignature(rgbDigest, new BigInteger(1, rgbR), new BigInteger(1, rgbS));                
            }
            
            default:
                throw new CoseException("Internal error");
        }
    }
}