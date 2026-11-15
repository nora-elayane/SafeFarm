import axios from 'axios';
const api=axios.create({baseURL:import.meta.env.VITE_API_URL||'http://localhost:8080/api',timeout:60000});
export const getFarms=()=>api.get('/farms').then(r=>r.data);export const saveFarm=(farm,id)=>api[id?'put':'post'](id?`/farms/${id}`:'/farms',farm).then(r=>r.data);export const deleteFarm=id=>api.delete(`/farms/${id}`);export const getAnalysis=id=>api.get(`/farms/${id}/analysis`).then(r=>r.data);export const getRotations=id=>api.get(`/farms/${id}/rotations`).then(r=>r.data);
